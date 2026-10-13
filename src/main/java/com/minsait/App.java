package com.minsait;

// camel-k: language=java
// camel-k: dependency=camel:platform-http
// camel-k: dependency=camel:jackson
// camel-k: dependency=mvn:ca.uhn.hapi.fhir:hapi-fhir-base:7.4.0
// camel-k: dependency=mvn:ca.uhn.hapi.fhir:hapi-fhir-structures-r4:7.4.0
// camel-k: trait=container.limit-memory=768Mi
// camel-k: trait=health.enabled=true
// camel-k: trait=route.enabled=true

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;

public class App {

    private static final Logger LOG = LoggerFactory.getLogger(App.class);
    private static final FhirContext FHIR_CTX = FhirContext.forR4();

    public static void main(String[] args) throws Exception {
        LOG.info("Iniciando servicio de integración Camel + FHIR R4...");

        CamelContext context = new DefaultCamelContext();
        context.addRoutes(new IntegrationRoutes());
        context.start();

        LOG.info("Camel iniciado exitosamente escuchando en el puerto 8080");

        // Hook para apagado limpio al recibir SIGTERM de Kubernetes/OpenShift
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Recibida señal de detención. Cerrando CamelContext...");
            try {
                context.stop();
            } catch (Exception e) {
                LOG.error("Error deteniendo el contexto", e);
            }
        }));

        // Mantiene el hilo principal vivo dentro del contenedor
        Thread.currentThread().join();
    }

    static class IntegrationRoutes extends RouteBuilder {

        @Override
        public void configure() {
            IParser fhirParser = FHIR_CTX.newJsonParser().setPrettyPrint(true);

            // ==========================================================
            // 1. HEALTH PROBES (Satisfacen los probes del Deployment de OpenShift)
            // ==========================================================
            from("netty-http:http://0.0.0.0:8080/q/health/live")
                .routeId("health-live-route")
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200))
                .setHeader("Content-Type", constant("application/json"))
                .setBody(constant("{\"status\":\"UP\",\"check\":\"liveness\"}"));

            from("netty-http:http://0.0.0.0:8080/q/health/ready")
                .routeId("health-ready-route")
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200))
                .setHeader("Content-Type", constant("application/json"))
                .setBody(constant("{\"status\":\"UP\",\"check\":\"readiness\"}"));

            // ==========================================================
            // 2. ENDPOINT FHIR DEMO (GET): Genera un recurso HL7 FHIR Patient R4
            // ==========================================================
            from("netty-http:http://0.0.0.0:8080/api/fhir/patient-mock")
                .routeId("fhir-patient-mock-route")
                .process(exchange -> {
                    Patient patient = new Patient();
                    patient.setId("paciente-poc-001");
                    
                    // Identificador nacional chileno (RUT)
                    Identifier rutIdentifier = new Identifier();
                    rutIdentifier.setSystem("urn:oid:cl-rut");
                    rutIdentifier.setValue("12345678-9");
                    patient.addIdentifier(rutIdentifier);

                    // Nombre del paciente
                    HumanName name = new HumanName();
                    name.setFamily("Pérez");
                    name.addGiven("Juan");
                    patient.addName(name);

                    // Género administrativo
                    patient.setGender(AdministrativeGender.MALE);

                    // Serializar con el parser oficial de HAPI FHIR R4
                    String jsonOutput = fhirParser.encodeResourceToString(patient);
                    exchange.getMessage().setBody(jsonOutput);
                })
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200))
                .setHeader("Content-Type", constant("application/fhir+json;charset=utf-8"));

            // ==========================================================
            // 3. INGESTA / TRANSFORMACIÓN FHIR (POST): Recibe, parsea y valida
            // ==========================================================
            from("netty-http:http://0.0.0.0:8080/api/fhir/patient")
                .routeId("fhir-patient-ingest-route")
                .process(exchange -> {
                    String rawJson = exchange.getMessage().getBody(String.class);

                    // Deserialización y validación estructural R4
                    Patient inputPatient = fhirParser.parseResource(Patient.class, rawJson);

                    String nombre = inputPatient.hasName() 
                        ? inputPatient.getNameFirstRep().getNameAsSingleString() 
                        : "Desconocido";

                    LOG.info("Recurso FHIR Patient procesado correctamente. Nombre: {}", nombre);

                    // Respuesta confirmando ingesta
                    exchange.getMessage().setBody(fhirParser.encodeResourceToString(inputPatient));
                })
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200))
                .setHeader("Content-Type", constant("application/fhir+json;charset=utf-8"));
        }
    }
}