# arx-anonymizer

Anonymizes entities using the [Arx](http://arx.deidentifier.org/) tool.

[![Build Status](https://travis-ci.org/sesam-community/arx.svg?branch=master)](https://travis-ci.org/sesam-community/arx)


## Workflow with Sesam

1. Export the full dataset (or a representative sample) that you want to anonymize to CSV
2. Import the CSV file into Arx
3. Configure each column, define the hierarchies, etc. and save the project as a .deid file
4. Store the .deid file somewhere accessible (Note! This file contains the original input, so make sure it's not publicly available)
5. Spin up this microservice and point it to the .deid file using env DEID_URL
6. This microservice can be used as a http_transform to anonymize entities that have the same structure as the CSV file.

## Example config

```
{
  "_id": "arx",
  "type": "system:microservice",
  "docker": {
    "environment": {
      "DEBUG": "true",
      "DEID_URL": "http://arx.deidentifier.org/?ddownload=2036"
    },
    "image": "sesamcommunity/arx",
    "port": 4567
  }
}
```

## Known issues

There is currently no way to select which transform to use. The optimum node will always be picked.

The optimum transform is based on the data stored in the .deid file, and additional data might produce another optimum transform. Therefore the training set should be updated on a regular basis.
