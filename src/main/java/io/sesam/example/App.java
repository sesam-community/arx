package io.sesam.example;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import org.apache.commons.io.FileUtils;
import org.deidentifier.arx.*;
import org.deidentifier.arx.criteria.KAnonymity;
import org.deidentifier.arx.criteria.LDiversity;
import org.deidentifier.arx.criteria.PrivacyCriterion;
import org.deidentifier.arx.gui.model.Model;
import org.deidentifier.arx.gui.model.ModelConfiguration;
import org.deidentifier.arx.gui.worker.WorkerLoad;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Spark;

import java.io.*;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class App {
    static boolean debug = Boolean.parseBoolean(System.getenv("DEBUG"));
    static Logger log = LoggerFactory.getLogger(App.class);

    static class ArxTransformer {
        final ARXAnonymizer anonymizer;
        final ARXLattice.ARXNode optimalNode;
        final DataDefinition input;
        final ARXConfiguration config;

        ArxTransformer(ARXAnonymizer anonymizer, ARXLattice.ARXNode optimalNode, DataDefinition input, ARXConfiguration config) {
            this.anonymizer = anonymizer;
            this.optimalNode = optimalNode;
            this.input = input;
            this.config = fixConfig(config);
        }

        static ARXConfiguration fixConfig(ARXConfiguration original) {
            ARXConfiguration config =  original.clone();
            // remove privacy models and outliers that doesn't make sense when processing deltas
            Set<PrivacyCriterion> privacyModels = config.getPrivacyModels();
            privacyModels.removeAll(config.getPrivacyModels(KAnonymity.class));
            privacyModels.removeAll(config.getPrivacyModels(LDiversity.class));
            config.setMaxOutliers(0d);
            return config;
        }

        /**
         * Load a transformer by finding the optimal node using the input stored in the .deid file
         */
        static ArxTransformer createFromDeid(File f) throws Exception {
            log.info("Reading config file");
            WorkerLoad loader = new WorkerLoad(f, null);
            loader.run(new NullProgressMonitor());
            Model model = loader.getResult();

            // stupid, I guess the UI calls this method before user can click anonymize
            model.getBLikenessModel();
            ARXAnonymizer anonymizer = model.createAnonymizer();

            ModelConfiguration mc = model.getInputConfig();
            Data input = mc.getInput();
            // stupid, need to release before we have locked anything?
            input.getHandle().release();

            log.info("Finding optimal node");
            ARXResult r = anonymizer.anonymize(input, mc.getConfig());
            if (!r.isResultAvailable()) {
                throw new RuntimeException("Unable to find optimal node");
            }
            ARXLattice.ARXNode globalOptimum = r.getGlobalOptimum();
            log.info("Arx preparation complete, transformation is: {}", globalOptimum.getTransformation());
            return new ArxTransformer(anonymizer, globalOptimum, input.getDefinition(), mc.getConfig());
        }

        public Map<String, Object> transform(Map<String, Object> in) throws IOException {
            Map<String, Object> out = Maps.newHashMap();
            out.put("_id", in.get("_id"));
            Data data = convertToData(in);

            // copy definition from "config"
            data.getDefinition().read(input);

            ARXResult r = anonymizer.anonymize(data, config);
            DataHandle output = r.getOutput(optimalNode, false);
            Iterator<String[]> i = output.iterator();
            if (!i.hasNext()) {
                throw new RuntimeException("Output is missing header row");
            }
            String[] h = i.next();
            if (!i.hasNext()) {
                throw new RuntimeException("Output is missing value row");
            }
            String[] v = i.next();

            for (int ix = 0; ix < h.length; ix++) {
                out.put(h[ix], v[ix]);
            }
            return out;
        }

        static Data convertToData(Map<String, Object> entity) {
            // convert map to header and value row
            ArrayList<String> header = Lists.newArrayList();
            ArrayList<String> value = Lists.newArrayList();
            for (String key : entity.keySet()) {
                if (!key.startsWith("_")) {
                    header.add(key);
                    value.add(String.valueOf(entity.get(key)));
                }
            }
            return Data.create(new String[][] { header.toArray(new String[header.size()]), value.toArray(new String[header.size()])});
        }
    }

    public static void main(String[] args) throws Exception {
        String deidUrl = System.getenv("DEID_URL");
        if (deidUrl == null) {
            throw new IllegalStateException("DEID_URL is required");
        }
        File tmpFile = File.createTempFile("deid", "foo");
        log.info("Downloading configuration from: {}", deidUrl);
        FileUtils.copyURLToFile(new URL(deidUrl), tmpFile);
        log.info("Download complete");
        ArxTransformer transformer = ArxTransformer.createFromDeid(tmpFile);

        Spark.post("/transform", (req, res) -> {
            res.type("application/json; charset=utf-8");

            try {
                Writer w = new OutputStreamWriter(res.raw().getOutputStream(), "utf-8");
                JsonWriter jw = new JsonWriter(w);
                Reader r = new InputStreamReader(req.raw().getInputStream(), "utf-8");
                JsonReader jr = new JsonReader(r);
                jw.beginArray();
                jr.beginArray();
                while (jr.hasNext()) {
                    // note that GSON will convert '55' to a double when we do it this way
                    Map<String,Object> input = new Gson().fromJson(jr, Map.class);
                    if (debug) {
                        log.info("Got input: {}", input);
                    }
                    Map<String,Object> output = transformer.transform(input);
                    new Gson().toJson(output, Map.class, jw);
                }
                jw.endArray();
                jr.endArray();
                jw.close();
                jr.close();
            } catch (Exception e) {
                log.error("Got exception", e);
                Spark.halt(500);
            }
            return "";
        });
        Spark.init();
    }
}