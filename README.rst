==============
arx-anonymizer
==============

Anonymizes entities using the Arx[1] tool.

===================
Workflow with Sesam
===================

1. Export the full dataset (or a representative sample) that you want to anonymize to CSV
2. Import the CSV file into Arx
3. Configure each column, define the hierarchies, etc. and save the project as a .deid file
4. Store the .deid file somewhere accessible (Note! This file contains the original input, so make sure it's safe)
5. Spin up this microservice and point it to the .deid file
6. This microservice can be used as a http_transform to anonymize entities.

[1] http://arx.deidentifier.org/