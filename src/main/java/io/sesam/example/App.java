package io.sesam.example;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
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
import java.util.*;

public class App {
    static boolean debug = Boolean.parseBoolean(System.getenv("DEBUG"));
    static Logger log = LoggerFactory.getLogger(App.class);

    static class ArxTransformer {
        final ARXAnonymizer anonymizer;
        final ARXLattice.ARXNode optimalNode;
        final DataDefinition input;
        final ARXConfiguration config;
        final String[] header;

        ArxTransformer(ARXAnonymizer anonymizer, ARXLattice.ARXNode optimalNode, DataDefinition input, ARXConfiguration config, String[] header) {
            this.anonymizer = anonymizer;
            this.optimalNode = optimalNode;
            this.input = input;
            this.config = fixConfig(config);
            this.header = header;
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
            String[] header = r.getOutput(false).iterator().next();
            log.info("Schema attributes: ", header);
            ARXLattice.ARXNode globalOptimum = r.getGlobalOptimum();
            log.info("Arx preparation complete, using transformation: {}", globalOptimum.getTransformation());
            return new ArxTransformer(anonymizer, globalOptimum, input.getDefinition(), mc.getConfig(), header);
        }

        ArrayList<Map<String,Object>> transform(ArrayList<Map<String, Object>> in) throws IOException {
            return convertResult(anonymizer.anonymize(createData(in), config).getOutput(optimalNode, false), in);
        }

        ArrayList<Map<String, Object>> convertResult(DataHandle result, ArrayList<Map<String, Object>> in) {
            Iterator<String[]> i = result.iterator();
            if (!i.hasNext()) {
                throw new RuntimeException("Output is missing header row");
            }
            String[] header = i.next();
            ArrayList<Map<String,Object>> out = new ArrayList<>();
            int rowId = 0;
            while (i.hasNext()) {
                String[] v = i.next();
                Map<String,Object> o = new LinkedHashMap<>();
                for (int ix = 0; ix < header.length; ix++) {
                    // assuming the output is ordered the same as the input (must be)
                    o.put("_id", in.get(rowId).get("_id"));
                    o.put(header[ix], v[ix]);
                }
                out.add(o);
                rowId++;
            }
            return out;
        }

        Data.DefaultData createData(ArrayList<Map<String, Object>> entities) {
            Data.DefaultData data = Data.create();
            data.add(header);
            for (Map<String, Object> entity : entities) {
                String[] row = new String[header.length];
                for (int i = 0; i < header.length; i++) {
                    // combination gson and String.valueOf might not work well for non-string types
                    row[i] = String.valueOf(entity.get(header[i]));
                }
                data.add(row);
            }
            // copy definition from "config"
            data.getDefinition().read(input);
            return data;
        }
    }

    public static void main(String[] args) throws Exception {
        if (debug) {
            log.info("Debug logging enabled");
        }
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
            Gson gson = new Gson();
            try {
                ArrayList<Map<String,Object>> entities = Lists.newArrayList();
                entities = gson.fromJson(new InputStreamReader(req.raw().getInputStream(), "utf-8"), entities.getClass());
                return gson.toJson(transformer.transform(entities));
            } catch (Exception e) {
                log.error("Got exception", e);
                Spark.halt(500);
                return "";
            }
        });
        Spark.init();
    }
}