package com.jettra.store.engine.insertion;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.hierarchy.StorageEngineType;
import com.jettra.store.engine.insertion.RecordModelPayloadHandler.CanonicalRecord;
import com.jettra.store.engine.models.RecordsEngine;
import com.jettra.store.engine.web.EngineRecordInsertionDialog;
import io.jettra.flux.core.Widget;
import io.jettra.flux.theme.Themes;
import io.jettra.flux.widgets.JettraFluxRecordForm;
import io.jettra.json.JettraJson;
import io.jettra.json.JsonObject;
import io.jettra.test.annotation.AfterEach;
import io.jettra.test.annotation.BeforeEach;
import io.jettra.test.annotation.DisplayName;
import io.jettra.test.annotation.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.jettra.test.core.JettraAssert.*;

/**
 * Unit and integration tests verifying RecordModelPayloadHandler,
 * JettraFluxRecordForm component rendering, canonical schema serialization,
 * zero-copy compaction, and persistence in RecordsEngine.
 */
@io.jettra.test.annotation.NotRequiresRunningServer
public class RecordModelPayloadHandlerTest {

    private Path tempDir;
    private JettraStorageEngine storageEngine;
    private RecordsEngine recordsEngine;
    private static final JettraJson JSON = new JettraJson();

    @BeforeEach
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("jettra_test_record_model_");
        storageEngine = new JettraStorageEngine(tempDir.toString());
        recordsEngine = new RecordsEngine(storageEngine);
        storageEngine.registerEngine("RECORDS", recordsEngine);
        storageEngine.start();
    }

    @AfterEach
    public void tearDown() {
        if (storageEngine != null) {
            storageEngine.stop();
        }
        if (tempDir != null && Files.exists(tempDir)) {
            try {
                Files.walk(tempDir)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            } catch (Exception ignored) {}
        }
    }

    @Test
    @DisplayName("Verify CanonicalRecord serialization matches exact JettraDB metadata contract")
    public void testCanonicalRecordSerialization() {
        Map<String, String> schema = new LinkedHashMap<>();
        schema.put("first_name", "String");
        schema.put("last_name", "String");
        schema.put("email", "String");
        schema.put("age", "Integer");
        schema.put("salary", "Double");
        schema.put("department", "String");
        schema.put("created_at", "String");
        schema.put("_table", "String");

        Map<String, Object> components = new LinkedHashMap<>();
        components.put("first_name", "John");
        components.put("last_name", "Doe");
        components.put("email", "john.doe@company.org");
        components.put("age", 34);
        components.put("salary", 85000.0);
        components.put("department", "ENGINEERING");
        components.put("created_at", "2026-09-07T10:00:00Z");
        components.put("_table", "employees");

        long ts = 1788809869770L;
        CanonicalRecord canonical = new CanonicalRecord(
                "com.jettra.model.EmployeeRecord",
                ts,
                1L,
                schema,
                components,
                "employees",
                "emp_101"
        );

        JsonObject obj = canonical.toJsonObject();
        assertEquals("com.jettra.model.EmployeeRecord", obj.getAsString("_recordClass"));
        assertEquals(ts, ((Number) obj.get("_timestamp")).longValue());
        assertEquals(1L, ((Number) obj.get("_version")).longValue());

        assertTrue(obj.has("_schema"));
        JsonObject sObj = (JsonObject) obj.get("_schema");
        assertEquals("String", sObj.getAsString("first_name"));
        assertEquals("Integer", sObj.getAsString("age"));
        assertEquals("Double", sObj.getAsString("salary"));

        assertTrue(obj.has("components"));
        JsonObject cObj = (JsonObject) obj.get("components");
        assertEquals("John", cObj.getAsString("first_name"));
        assertEquals("Doe", cObj.getAsString("last_name"));
        assertEquals("john.doe@company.org", cObj.getAsString("email"));
        assertEquals(34, ((Number) cObj.get("age")).intValue());
        assertEquals(85000.0, ((Number) cObj.get("salary")).doubleValue(), 0.001);

        // Test compact buffer serialization
        byte[] bytes = canonical.toCompactBytes();
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);
        String decoded = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(decoded.contains("\"_recordClass\":\"com.jettra.model.EmployeeRecord\""));
        assertTrue(decoded.contains("\"first_name\":\"John\""));
    }

    @Test
    @DisplayName("Verify Schema Inference and Type Parsing using Java 25 Pattern Matching")
    public void testSchemaInferenceAndTypeMatching() {
        assertEquals("Integer", RecordModelPayloadHandler.inferType(42));
        assertEquals("Integer", RecordModelPayloadHandler.inferType("42"));
        assertEquals("Long", RecordModelPayloadHandler.inferType(9999999999L));
        assertEquals("Long", RecordModelPayloadHandler.inferType("9999999999"));
        assertEquals("Double", RecordModelPayloadHandler.inferType(85000.50));
        assertEquals("Double", RecordModelPayloadHandler.inferType("85000.50"));
        assertEquals("Boolean", RecordModelPayloadHandler.inferType(true));
        assertEquals("Boolean", RecordModelPayloadHandler.inferType("true"));
        assertEquals("String", RecordModelPayloadHandler.inferType("ENGINEERING"));

        assertEquals(100, RecordModelPayloadHandler.parseValueForType("100", "Integer"));
        assertEquals(12345678900L, RecordModelPayloadHandler.parseValueForType("12345678900", "Long"));
        assertEquals(99.99, (Double) RecordModelPayloadHandler.parseValueForType("99.99", "Double"), 0.001);
        assertEquals(true, RecordModelPayloadHandler.parseValueForType("true", "Boolean"));
        assertEquals("Acme Corp", RecordModelPayloadHandler.parseValueForType("Acme Corp", "String"));
    }

    @Test
    @DisplayName("Verify RecordModelPayloadHandler Parameter Validation & Stream Gatherer checking")
    public void testValidation() {
        // Valid params
        Map<String, String> valid = new LinkedHashMap<>();
        valid.put("target_coll", "employees");
        valid.put("target_id", "emp_200");
        valid.put("rec_class", "com.jettra.model.EmployeeRecord");
        valid.put("rec_payload", "{\"first_name\":\"Alice\",\"age\":29}");

        ValidationResult res = RecordModelPayloadHandler.validateParameters(valid);
        assertTrue(res.isValid());

        // Missing table
        Map<String, String> noTable = new LinkedHashMap<>(valid);
        noTable.remove("target_coll");
        assertFalse(RecordModelPayloadHandler.validateParameters(noTable).isValid());

        // Invalid target_id with '/'
        Map<String, String> invalidId = new LinkedHashMap<>(valid);
        invalidId.put("target_id", "emp/invalid");
        ValidationResult idRes = RecordModelPayloadHandler.validateParameters(invalidId);
        assertFalse(idRes.isValid());
        assertTrue(idRes.errors().stream().anyMatch(e -> e.contains("no puede contener")));
    }

    @Test
    @DisplayName("Verify Persistence dispatch to RecordsEngine and Storage Core")
    public void testDispatchToStorageEngine() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("target_coll", "personnel");
        params.put("target_id", "emp_505");
        params.put("rec_class", "com.jettra.model.EmployeeRecord");
        params.put("rec_payload", """
        {
          "_recordClass": "com.jettra.model.EmployeeRecord",
          "_timestamp": 1788809869770,
          "_version": 1,
          "_schema": {
            "first_name": "String",
            "last_name": "String",
            "email": "String",
            "age": "Integer",
            "salary": "Double",
            "department": "String",
            "created_at": "String",
            "_table": "String"
          },
          "components": {
            "first_name": "John",
            "last_name": "Doe",
            "email": "john.doe@company.org",
            "age": 34,
            "salary": 85000,
            "department": "ENGINEERING",
            "created_at": "2026-09-07T10:00:00Z",
            "_table": "personnel"
          }
        }
        """);

        CanonicalRecord canonical = RecordModelPayloadHandler.buildCanonicalRecord("enterprise_db", "personnel", "emp_505", params);
        assertEquals("com.jettra.model.EmployeeRecord", canonical.recordClass());
        assertEquals("personnel", canonical.table());
        assertEquals("emp_505", canonical.recordId());
        assertEquals(34, ((Number) canonical.components().get("age")).intValue());

        InsertionResult insertResult = RecordModelPayloadHandler.dispatchToEngine(storageEngine, "enterprise_db", canonical);
        assertTrue(insertResult.success());
        assertEquals("RECORDS", insertResult.engine());
        assertEquals("enterprise_db", insertResult.database());
        assertEquals("personnel", insertResult.unit());
        assertEquals("emp_505", insertResult.id());

        // Verify storage core binary content
        String internalKey = "rec:enterprise_db:personnel:emp_505";
        byte[] storedBytes = storageEngine.getStorageCore().get(internalKey);
        assertNotNull(storedBytes, "Record must be stored in storage core under internal key: " + internalKey);
        String json = new String(storedBytes, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"_recordClass\": \"com.jettra.model.EmployeeRecord\"") || json.contains("\"_recordClass\":\"com.jettra.model.EmployeeRecord\""));
        assertTrue(json.contains("\"first_name\": \"John\"") || json.contains("\"first_name\":\"John\""));
        assertTrue(json.contains("\"components\""));
        assertTrue(json.contains("\"_schema\""));
    }

    @Test
    @DisplayName("Verify EngineType and StorageEngineType resolution for RECORD and RECORDS")
    public void testEngineTypeResolution() {
        EngineType recType1 = EngineType.fromKey("RECORD");
        EngineType recType2 = EngineType.fromKey("RECORDS");
        EngineType recType3 = EngineType.fromKey("relational");

        assertInstanceOf(EngineType.RelationalRecords.class, recType1);
        assertInstanceOf(EngineType.RelationalRecords.class, recType2);
        assertInstanceOf(EngineType.RelationalRecords.class, recType3);

        assertEquals("ULTRA-FAST", recType1.badge());
        assertEquals("Record (Java 25)", recType1.displayName());
        assertEquals("fas fa-microchip", recType1.icon());

        // Test StorageEngineType.fromString
        var optType = StorageEngineType.fromString("RECORD");
        assertTrue(optType.isPresent());
        assertEquals(StorageEngineType.RELATIONAL_RECORDS, optType.get());

        // Test EngineInsertionFactory strategy lookup by "RECORD"
        var strat = EngineInsertionFactory.getStrategy("RECORD");
        assertNotNull(strat);
        assertEquals("RECORDS", strat.engineType().key());
    }

    @Test
    @DisplayName("Verify JettraFluxRecordForm widget rendering in JettraFlux")
    public void testJettraFluxRecordFormRendering() {
        JettraFluxRecordForm form = JettraFluxRecordForm.of("insert_rec", "com.jettra.model.EmployeeRecord", "employees")
                .sampleEmployeeRecord();

        String html = form.render(Themes.FlatTheme());
        assertNotNull(html);
        assertTrue(html.contains("id=\"insert_rec_record_editor_container\""));
        assertTrue(html.contains("id=\"insert_rec_class\""));
        assertTrue(html.contains("id=\"insert_rec_table\""));
        assertTrue(html.contains("id=\"insert_rec_payload\""));
        assertTrue(html.contains("com.jettra.model.EmployeeRecord"));
        assertTrue(html.contains("employees"));
        assertTrue(html.contains("first_name"));
        assertTrue(html.contains("salary"));
        assertTrue(html.contains("Agregar Campo"));
        assertTrue(html.contains("Inferir Esquema"));
        assertTrue(html.contains("JSON Canónico"));
        assertTrue(html.contains("JettraFluxRecordForm"));
    }

    public enum EstadoCivil { SOLTERO, CASADO, DIVORCIADO }
    public record Pais(String codigo, String nombre) {}
    public record Persona(
        String id,
        String nombre,
        Pais pais,
        java.time.LocalDate fechaNacimiento,
        java.time.Instant creadoEn,
        List<String> tags,
        EstadoCivil estadoCivil
    ) {}

    @Test
    @DisplayName("Verify Temporal and Primitive Type Inference")
    public void testTemporalAndPrimitiveTypeInference() {
        assertEquals("LocalDate", RecordModelPayloadHandler.inferType(java.time.LocalDate.of(2026, 9, 8)));
        assertEquals("LocalDate", RecordModelPayloadHandler.inferType("2026-09-08"));
        assertEquals("LocalTime", RecordModelPayloadHandler.inferType(java.time.LocalTime.of(14, 30, 0)));
        assertEquals("LocalTime", RecordModelPayloadHandler.inferType("14:30:00"));
        assertEquals("LocalDateTime", RecordModelPayloadHandler.inferType(java.time.LocalDateTime.of(2026, 9, 8, 14, 30)));
        assertEquals("LocalDateTime", RecordModelPayloadHandler.inferType("2026-09-08T14:30:00"));
        assertEquals("Instant", RecordModelPayloadHandler.inferType(java.time.Instant.parse("2026-09-08T14:30:00Z")));
        assertEquals("Instant", RecordModelPayloadHandler.inferType("2026-09-08T14:30:00Z"));
        assertEquals("Date", RecordModelPayloadHandler.inferType(new java.util.Date()));
        assertEquals("Byte", RecordModelPayloadHandler.inferType((byte) 8));
        assertEquals("Short", RecordModelPayloadHandler.inferType((short) 16));
        assertEquals("Character", RecordModelPayloadHandler.inferType('J'));
        assertEquals("Float", RecordModelPayloadHandler.inferType(3.14f));
    }

    @Test
    @DisplayName("Verify Collections, Enums, and Nested Records Inference and Parsing")
    public void testCollectionsAndEnumsAndNestedRecordsInference() {
        assertEquals("List<String>", RecordModelPayloadHandler.inferType(List.of("alpha", "beta")));
        assertEquals("List<String>", RecordModelPayloadHandler.inferType(new io.jettra.json.JsonArray()));
        assertEquals("List<String>", RecordModelPayloadHandler.inferType("[\"alpha\", \"beta\"]"));
        assertEquals("Set<String>", RecordModelPayloadHandler.inferType(Set.of("unique")));
        assertEquals("Enum<EstadoCivil>", RecordModelPayloadHandler.inferType(EstadoCivil.CASADO));
        
        Pais p = new Pais("PA", "Panamá");
        assertEquals("Pais", RecordModelPayloadHandler.inferType(p));

        // Test parsing collections
        Object parsedList = RecordModelPayloadHandler.parseValueForType("[\"reading\", \"coding\"]", "List<String>");
        assertInstanceOf(io.jettra.json.JsonArray.class, parsedList);
        io.jettra.json.JsonArray arr = (io.jettra.json.JsonArray) parsedList;
        assertEquals(2, arr.size());
        assertEquals("reading", arr.getAsString(0));

        // Test parsing nested object
        Object parsedNested = RecordModelPayloadHandler.parseValueForType("{\"codigo\":\"PA\",\"nombre\":\"Panamá\"}", "Pais");
        assertInstanceOf(JsonObject.class, parsedNested);
        JsonObject jo = (JsonObject) parsedNested;
        assertEquals("PA", jo.getAsString("codigo"));
        assertEquals("Panamá", jo.getAsString("nombre"));
    }

    @Test
    @DisplayName("Verify Persona with nested Pais record dispatch and storage in RecordsEngine")
    public void testNestedRecordPersonaWithPaisDispatch() {
        Persona persona = new Persona(
            "per_001",
            "Aristides",
            new Pais("PA", "Panamá"),
            java.time.LocalDate.of(1980, 5, 20),
            java.time.Instant.parse("2026-09-08T10:00:00Z"),
            List.of("java", "databases"),
            EstadoCivil.CASADO
        );

        RecordsEngine recEngine = (RecordsEngine) storageEngine.getEngine("RECORDS");
        recEngine.saveRecordObject("personas", persona.id(), persona);

        // Retrieve and assert structured JSON
        JsonObject retrieved = recEngine.getRecord("personas", "per_001");
        assertNotNull(retrieved);
        assertTrue(retrieved.has("_schema"));
        assertTrue(retrieved.has("components"));

        JsonObject components = retrieved.getAsJsonObject("components");
        assertEquals("per_001", components.getAsString("id"));
        assertEquals("Aristides", components.getAsString("nombre"));
        assertEquals("1980-05-20", components.getAsString("fechaNacimiento"));
        assertEquals("CASADO", components.getAsString("estadoCivil"));

        // Check nested record Pais
        JsonObject paisJson = components.getAsJsonObject("pais");
        assertNotNull(paisJson);
        assertEquals("PA", paisJson.getAsString("codigo"));
        assertEquals("Panamá", paisJson.getAsString("nombre"));

        // Check tags array
        io.jettra.json.JsonArray tagsArr = components.getAsJsonArray("tags");
        assertNotNull(tagsArr);
        assertEquals(2, tagsArr.size());
        assertEquals("java", tagsArr.getAsString(0));
        assertEquals("databases", tagsArr.getAsString(1));
    }

    @Test
    @DisplayName("Verify EngineRecordInsertionDialog integration contains RECORD pill with badge")
    public void testEngineRecordInsertionDialogRecordPill() {
        Widget dialog = EngineRecordInsertionDialog.build("/engines", "RECORD", "corp_db", "employees");
        String html = dialog.render(Themes.FlatTheme());

        assertTrue(html.contains("engine_tab_btn_RECORDS"));
        assertTrue(html.contains("ULTRA-FAST"));
        assertTrue(html.contains("Record (Java 25)"));
        assertTrue(html.contains("insert_rec"));
        assertTrue(html.contains("rec_payload"));
    }
}
