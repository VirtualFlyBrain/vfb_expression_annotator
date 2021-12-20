package vfb.ebi.expressionannotator;

import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.util.mansyntax.ManchesterOWLSyntaxParser;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Program that annotates entities in an ontology with annotation assertions by classifying class expressions provided in a configuration file.
 */
public class VFBExpressionAnnotator {
    OWLDataFactory df = OWLManager.getOWLDataFactory();
    private final ManchesterOWLSyntaxParser parser = OWLManager.createManchesterParser();
    private final File ontology_file;
    private final File outfile;
    private final OWLAnnotationProperty ap_anno;
    private static final String OBONS = "http://purl.obolibrary.org/obo/";


    private Map<String,String> classExpressionNeoLabelMap = new HashMap<>();
    private Map<String, String> customCurieMap = new HashMap<>();
    private Map<OWLEntity, String> curies = new HashMap<>();
    private Set<OWLClass> filterout = new HashSet<>();
    private Map<OWLEntity, Set<String>> annotations = new HashMap<>();
	private boolean unique_annotations = false;


    private VFBExpressionAnnotator(File ontology_file, File config_file, String annotation_iri, boolean unique_annotations, File outfile) throws IOException, OWLOntologyCreationException, OWLOntologyStorageException {
        this.ontology_file = ontology_file;
        this.outfile = outfile;
        this.ap_anno = df.getOWLAnnotationProperty(IRI.create(annotation_iri));
        this.unique_annotations = unique_annotations;
        prepareConfig(config_file);
        run();
    }

    private void run() throws IOException, OWLOntologyCreationException, OWLOntologyStorageException {
        log("Loading ontology..");
        OWLOntology o = OWLManager.createOWLOntologyManager().loadOntology(IRI.create(ontology_file));

        log("Preparing CURIEs");
        prepareCuries(o);

        log("Preparing Class Expression Parser..");
        Map<String,OWLEntity> entityMap = prepareEntityMap(o);
        parser.setOWLEntityChecker(new N2OEntityChecker(entityMap));

        log("Preparing reasoner..");
        OWLReasoner r = new ElkReasonerFactory().createReasoner(o);
        filterout.add(o.getOWLOntologyManager().getOWLDataFactory().getOWLThing());
        filterout.add(o.getOWLOntologyManager().getOWLDataFactory().getOWLNothing());
        filterout.addAll(r.getUnsatisfiableClasses().getEntities());
		if (this.unique_annotations) {
			addUniqueNodeLabels(r);
		} else {
			addDynamicNodeLabels(r);
		}

        log("Exporting result");
        OWLOntologyManager man2 = OWLManager.createOWLOntologyManager();
        Set<OWLAxiom> axioms = new HashSet<>();
        for(OWLEntity e:annotations.keySet()) {
            for(String s:annotations.get(e)) {
                OWLAnnotationAssertionAxiom ax = df.getOWLAnnotationAssertionAxiom(ap_anno,e.getIRI(),df.getOWLLiteral(s));
                axioms.add(ax);
            }
        }
        OWLOntology o_out = man2.createOntology(axioms,IRI.create("http://annotated_entities.org/annotations.owl"));
        man2.saveOntology(o_out,new FileOutputStream(outfile));
    }

    private void prepareCuries(OWLOntology o) {
        // Save all IRI prefixes in reverse order to allow overlapping iri prefixes
        List<String> curiemap = new ArrayList<>(customCurieMap.values());
        Collections.sort(curiemap, Collections.reverseOrder());

        Map<String, String> swapped = new HashMap<>();
        for(Map.Entry<String,String> entry : customCurieMap.entrySet())
            swapped.put(entry.getValue(), entry.getKey());

        Set<OWLEntity> entities = new HashSet<>(o.getClassesInSignature(Imports.INCLUDED));
        entities.addAll(o.getIndividualsInSignature());
        entities.addAll(o.getObjectPropertiesInSignature());

        //curiemap.forEach(System.out::println);

        for(OWLEntity e:entities) {
            String iri_s = e.getIRI().toString();
            String curie = "";
            if(iri_s.startsWith(OBONS)) {
                String fragement = iri_s.replaceAll(OBONS,"");
                //log("FRAGMENT: "+fragement);
                if(fragement.matches("^[a-zA-z0-9]+[_][a-zA-z0-9]+$")) {
                    curie = fragement.replaceAll("_",":");
                } else {
                    curie = getCurieFromCurieMap(curiemap, swapped, iri_s);
                }
            } else {
                curie = getCurieFromCurieMap(curiemap, swapped, iri_s);
            }
            if(curie.isEmpty()) {
               log("CURIE could not be created for: "+iri_s);
            } else {
                curies.put(e,curie);
            }
        }
    }

    private String getCurieFromCurieMap(List<String> curiemap, Map<String, String> swapped, String iri_s) {
        String curie = "";
        for(String iri_prefix:curiemap) {
            if(iri_s.startsWith(iri_prefix)) {
                String prefix = swapped.get(iri_prefix);
                String fragement = iri_s.replaceAll(iri_prefix, "");
                if (fragement.contains(prefix + "_")) {
                    curie = fragement.replaceAll("_", ":");
                } else {
                    curie = prefix + ":" + fragement;
                }
            }
        }
        return curie;
    }


    @SuppressWarnings("unchecked")
    private void prepareConfig(File config_file) throws IOException {
        Yaml yaml = new Yaml();
        InputStream inputStream = new FileInputStream(config_file);
        Map<String, Object> configs = yaml.load(inputStream);

        if (configs.containsKey("neo_node_labelling")) {
            Object pm = configs.get("neo_node_labelling");
            if (pm instanceof ArrayList) {
                for (Object pmm : ((ArrayList) pm)) {
                    if (pmm instanceof HashMap) {
                        HashMap<String, Object> pmmhm = (HashMap<String, Object>) pmm;
                        if (pmmhm.containsKey("classes")) {
                            ArrayList expressions = (ArrayList) pmmhm.get("classes");
                            String label = "";
                            if (pmmhm.containsKey("label")) {
                                label = pmmhm.get("label").toString();
                            }
                            for(Object o:expressions) {
                                String s = o.toString();
                                classExpressionNeoLabelMap.put(s,label);
                            }
                        }
                    }
                }
            }
        }

        if (configs.containsKey("curie_map")) {
            if (configs.get("curie_map") instanceof HashMap) {
                HashMap<String, String> map = (HashMap<String, String>) configs.get("curie_map");
                for(String k:map.keySet()) {
                    customCurieMap.put(k,map.get(k));
                }
            }
        }

    }


    private void addDynamicNodeLabels(OWLReasoner r) {
        for (String ces : classExpressionNeoLabelMap.keySet()) {
            String label = classExpressionNeoLabelMap.get(ces);
            try {
                OWLClassExpression ce = parseExpression(ces);
                if (label.isEmpty()) {
                    log("During adding of dynamic neo labels, an empty label was encountered in conjunction with a complex class expression (" + ce + "). The label was not added.");
                }
                if (!label.isEmpty()) {
                    for (OWLClass sc : getSubClasses(r, ce, false)) addAnnotation(sc, label);
                    for (OWLNamedIndividual sc : getInstances(r, ce, false)) addAnnotation(sc, label);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void addAnnotation(OWLEntity sc, String label) {
        if(!annotations.containsKey(sc)) {
            annotations.put(sc,new HashSet<>());
        }
        annotations.get(sc).add(label);
    }

    private OWLClassExpression parseExpression(String manchesterSyntaxString) {
        parser.setStringToParse(manchesterSyntaxString);
        return parser.parseClassExpression();
    }
    
    private OWLClassExpression parseExpressionWithoutFail(String manchesterSyntaxString) {
    	OWLClassExpression expression = null;
        try {
			parser.setStringToParse(manchesterSyntaxString);
			expression = parser.parseClassExpression();
		} catch (Exception e) {
			e.printStackTrace();
		}
        return expression;
    }

    private Map<String, OWLEntity> prepareEntityMap(OWLOntology o) {
        Map<String, OWLEntity> entityMap = new HashMap<>();
        Set<OWLEntity> entities = new HashSet<>(o.getClassesInSignature(Imports.INCLUDED));
        entities.addAll(o.getIndividualsInSignature());
        entities.addAll(o.getObjectPropertiesInSignature());

        for(OWLEntity e:entities) {
            String iri = e.getIRI().toString();
            String curie = curies.get(e);
            if(curie != null) {
                entityMap.put(iri, e);
                entityMap.put(curie, e);
            }
        }
        return  entityMap;
    }

    private Set<OWLClass> getSubClasses(OWLReasoner r, OWLClassExpression e, boolean direct) {
        Set<OWLClass> subclasses = new HashSet<>(r.getSubClasses(e, direct).getFlattened());
        subclasses.addAll(r.getEquivalentClasses(e).getEntities());
        if(e.toString().contains("http://purl.obolibrary.org/obo/FBbt_00005123")) {
            System.out.println(e);
        }
        subclasses.removeAll(filterout.stream().filter(OWLEntity::isOWLClass).map(OWLEntity::asOWLClass).collect(Collectors.toSet()));
        if (e.isClassExpressionLiteral()) {
            subclasses.remove(e.asOWLClass());
        }
        if(e.toString().contains("http://purl.obolibrary.org/obo/FBbt_00005123")) {
            System.out.println(e);
        }
        return subclasses;
    }

    private Set<OWLNamedIndividual> getInstances(OWLReasoner r, OWLClassExpression e, boolean direct) {
        Set<OWLNamedIndividual> instances = new HashSet<>(r.getInstances(e, direct).getFlattened());
        instances.removeAll(filterout.stream().filter(OWLEntity::isOWLNamedIndividual).map(OWLEntity::asOWLNamedIndividual).collect(Collectors.toSet()));
        return instances;
    }
    
	/**
	 * Adds node labels through considering the hierarchy between labels. Only keeps
	 * the most specific labels. For example if node has two labels: ["cell",
	 * "neuron"], removes cells since it is super class of neuron.
	 * 
	 * @param r reasoner
	 */
	private void addUniqueNodeLabels(OWLReasoner r) {
		Map<String, Set<String>> labelMapping = getSublabelMapping(r);
		Map<OWLEntity, Set<String>> labelAssignments = new HashMap<>();
		for (String ces : classExpressionNeoLabelMap.keySet()) {
			String label = classExpressionNeoLabelMap.get(ces);
			try {
				OWLClassExpression ce = parseExpression(ces);
				if (label.isEmpty()) {
					log("During adding of dynamic neo labels, an empty label was encountered in conjunction with a complex class expression ("
							+ ce + "). The label was not added.");
				}
				if (!label.isEmpty()) {
					for (OWLClass sc : getSubClasses(r, ce, false))
						addLabelAssignment(labelAssignments, sc, label);
					for (OWLNamedIndividual sc : getInstances(r, ce, false))
						addLabelAssignment(labelAssignments, sc, label);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		for (OWLEntity entity : labelAssignments.keySet()) {
			Set<String> labels = labelAssignments.get(entity);
			for (String label : labels) {
				Set<String> subLabels = labelMapping.get(label);
				if (subLabels.isEmpty() || Collections.disjoint(labels, subLabels)) {
					addAnnotation(entity, label);
				}
			}
		}
	}
    
    private void addLabelAssignment(Map<OWLEntity, Set<String>> labelAssignments, OWLEntity entity, String label) {
    	if(labelAssignments.containsKey(entity)) {
    		labelAssignments.get(entity).add(label);
    	} else {
    		Set<String> set = new HashSet<>();
    		set.add(label);
    		labelAssignments.put(entity, set);
    	}
    }
    
	/**
	 * Builds a label hierarchy based on the hierarchy of label related class expressions.
	 * 
	 * @param reasoner reasoned ontology
	 * @return Map of label- list of sub-labels (more specific labels)
	 */
	private Map<String, Set<String>> getSublabelMapping(OWLReasoner reasoner) {
		Map<String, Set<String>> subLabelMapping = new HashMap<>();
		for (String ces : classExpressionNeoLabelMap.keySet()) {
			OWLClassExpression ce = parseExpressionWithoutFail(ces);
			String label = classExpressionNeoLabelMap.get(ces);
			subLabelMapping.put(label, new HashSet<>());
			for (String cesCompare : classExpressionNeoLabelMap.keySet()) {
				String labelCompare = classExpressionNeoLabelMap.get(cesCompare);
				OWLClassExpression ceCompare = parseExpressionWithoutFail(cesCompare);
				if (ce != null && ceCompare != null && !label.equals(labelCompare)) {
					OWLAxiom axiom = df.getOWLSubClassOfAxiom(ceCompare, ce);
					if (reasoner.isEntailed(axiom)) {
						System.out.println("ENTAILED: " + labelCompare + "  subclassOf  " + label);
						subLabelMapping.get(label).add(labelCompare);
					}
				}
			}
		}
		return subLabelMapping;
	}

    private void log(Object o) {
        System.out.println(o);
    }

	public static void main(String[] args) throws OWLOntologyCreationException, IOException, OWLOntologyStorageException {
        String ontology_path = args[0];
        String config_path = args[1];
        String annotation_iri = args[2];
        String outfile_path = args[3];

		boolean unique_annotations = false;
		if (args.length > 4) {
			unique_annotations = Boolean.parseBoolean(args[4]);
		}

//		String ontology_path = "/home/huseyin/Downloads/fbbt.owl";
//		String config_path = "/home/huseyin/workspace/vfb-pipeline-config/config/prod/neo4j2owl-config.yaml";
//		String annotation_iri = "http://n2o.neo/property/nodeLabel";
//		String outfile_path = "/home/huseyin/Downloads/annotations2.owl";
//		unique_annotations = true;

		File ontology_file = new File(ontology_path);
		File config_file = new File(config_path);
		File outfile = new File(outfile_path);

		new VFBExpressionAnnotator(ontology_file, config_file, annotation_iri, unique_annotations, outfile);
	}
}
