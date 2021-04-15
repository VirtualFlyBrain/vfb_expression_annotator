# VFB expression annotator

This tool annotates expressions in a VFB config file using a specific configurable annotation property.

You can build the jar with

```
mvn clean package
``` 

Example parameters:
ontology_path = "/Users/matentzn/pipeline/vfb-pipeline-dumps/test/pdb.owl";
config_path = "/Users/matentzn/pipeline/vfb-prod/neo4j2owl-config.yaml";
annotation_iri = "http://n2o.neo/property/nodeLabel";
outfile_path = "/Users/matentzn/pipeline/vfb-pipeline-dumps/test/annotations.owl";*/

Example use:

```
inferred_annotation.owl:
	java -jar infer-annotate.jar $(ontology_path) $(config_path) $(annotation_iri) $(outfile_path)
```