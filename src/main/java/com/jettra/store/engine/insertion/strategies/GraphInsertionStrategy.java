package com.jettra.store.engine.insertion.strategies;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.insertion.EngineRecordInsertionStrategy;
import com.jettra.store.engine.insertion.EngineRecordPayload.GraphPayload;
import com.jettra.store.engine.insertion.EngineType;
import com.jettra.store.engine.insertion.InsertionResult;
import com.jettra.store.engine.insertion.JsonPayloadHelper;
import com.jettra.store.engine.insertion.ValidationResult;
import com.jettra.store.engine.models.GraphEngine;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.json.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strategy implementation for Property Graph (Vertices and Directed Edges) storage engine.
 */
public class GraphInsertionStrategy implements EngineRecordInsertionStrategy<GraphPayload> {

    @Override
    public EngineType engineType() {
        return new EngineType.Graph();
    }

    @Override
    public ValidationResult validate(Map<String, String> params) {
        List<String> errors = new ArrayList<>();
        String mode = params.getOrDefault("graph_mode", "node").toLowerCase();
        if ("edge".equals(mode)) {
            String from = params.get("edge_from");
            String to = params.get("edge_to");
            if (from == null || from.isBlank()) errors.add("El ID de nodo origen ('From') es requerido para una arista.");
            if (to == null || to.isBlank()) errors.add("El ID de nodo destino ('To') es requerido para una arista.");
            String label = params.get("edge_label");
            if (label == null || label.isBlank()) errors.add("La etiqueta de la relación ('Edge Label') es requerida.");
        } else {
            String id = params.get("target_id");
            if (id == null || id.isBlank()) errors.add("El ID del vértice ('Node ID') es requerido.");
            String label = params.get("node_label");
            if (label == null || label.isBlank()) errors.add("La etiqueta del vértice ('Node Label') es requerida.");
        }
        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(errors);
    }

    @Override
    public GraphPayload parsePayload(String database, String unit, String id, Map<String, String> params) {
        String mode = params.getOrDefault("graph_mode", "node").toLowerCase();
        if ("edge".equals(mode)) {
            String from = params.getOrDefault("edge_from", "node_1");
            String to = params.getOrDefault("edge_to", "node_2");
            String label = params.getOrDefault("edge_label", (unit != null && !unit.isBlank()) ? unit : "CONNECTED_TO");
            String rawProps = params.getOrDefault("edge_props", "{}");
            JsonObject props = JsonPayloadHelper.parseJsonOrWrap(rawProps, "properties");
            String edgeId = from + "->" + label + "->" + to;
            return new GraphPayload("edge", edgeId, label, from, to, props, rawProps);
        } else {
            String nodeId = (id != null && !id.isBlank()) ? id : params.getOrDefault("target_id", "vertex_01");
            String label = params.getOrDefault("node_label", (unit != null && !unit.isBlank()) ? unit : "UserNode");
            String rawProps = params.getOrDefault("node_props", "{}");
            JsonObject props = JsonPayloadHelper.parseJsonOrWrap(rawProps, "properties");
            props.addProperty("label", label);
            return new GraphPayload("node", nodeId, label, null, null, props, rawProps);
        }
    }

    @Override
    public InsertionResult executeInsert(JettraStorageEngine storageEngine, String database, GraphPayload payload) {
        GraphEngine graphEngine = (GraphEngine) storageEngine.getEngine("GRAPH");
        if (graphEngine == null) {
            return InsertionResult.ofError("GRAPH", database, payload.label(), payload.id(), "GraphEngine not registered in storage orchestrator");
        }
        if ("edge".equalsIgnoreCase(payload.mode())) {
            graphEngine.addEdge(database, payload.fromId(), payload.toId(), payload.label(), payload.properties());
            return InsertionResult.ofSuccess("GRAPH", database, payload.label(), payload.id(),
                    "Graph Edge [" + payload.fromId() + " -> " + payload.label() + " -> " + payload.toId() + "] successfully created", 1);
        } else {
            graphEngine.addNode(database, payload.id(), payload.properties());
            return InsertionResult.ofSuccess("GRAPH", database, payload.label(), payload.id(),
                    "Graph Vertex '" + payload.id() + "' (Label: " + payload.label() + ") successfully added", 1);
        }
    }

    @Override
    public Widget buildEngineFormFields(String currentDb, String currentUnit) {
        String sampleProps = "{\n  \"weight\": 1.0,\n  \"created_at\": \"2026-09-07\",\n  \"active\": true\n}";

        return Div.of(
            Div.of(
                Div.of(
                    Label.of("Tipo de Entidad Grafo:").modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    Div.of(
                        RadioButton.of("graph_mode_node", "Vértice / Nodo (Vertex)")
                            .name("graph_mode")
                            .value("node")
                            .checked(true)
                            .onChange("document.getElementById('graph_node_fields').style.display='block';document.getElementById('graph_edge_fields').style.display='none';")
                            .modifier(new Modifier().style("margin-right:16px; font-size:12px; color:var(--j-text-primary); cursor:pointer;")),
                        RadioButton.of("graph_mode_edge", "Arista / Relación (Edge)")
                            .name("graph_mode")
                            .value("edge")
                            .checked(false)
                            .onChange("document.getElementById('graph_node_fields').style.display='none';document.getElementById('graph_edge_fields').style.display='block';")
                            .modifier(new Modifier().style("font-size:12px; color:var(--j-text-primary); cursor:pointer;"))
                    ).modifier(new Modifier().style("display:flex; align-items:center; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px;"))
                ).modifier(new Modifier().style("flex:1;"))
            ).modifier(new Modifier().style("margin-bottom:12px;")),

            // Node specific fields
            Div.of(
                Div.of(
                    Label.of("Etiqueta de Nodo (Node Label / Type):").modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("insert_graph_node_label").binding("node_label").value(currentUnit != null ? currentUnit : "Person")
                        .modifier(new Modifier().style("width:100%; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12.5px;"))
                ).modifier(new Modifier().style("margin-bottom:12px;")),
                JettraFluxJsonEditor.of("insert_graph_node_props", "Propiedades del Vértice (JSON)", "{\n  \"name\": \"Alice Vance\",\n  \"department\": \"Research\",\n  \"reputation\": 98\n}")
                    .name("node_props").height("160px")
            ).id("graph_node_fields"),

            // Edge specific fields
            Div.of(
                Div.of(
                    Div.of(
                        Label.of("Nodo Origen (From ID):").modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                        TextField.of().id("insert_edge_from").binding("edge_from").value("node_alice")
                            .modifier(new Modifier().style("width:100%; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12.5px;"))
                    ).modifier(new Modifier().style("flex:1;")),
                    Div.of(
                        Label.of("Nodo Destino (To ID):").modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                        TextField.of().id("insert_edge_to").binding("edge_to").value("node_bob")
                            .modifier(new Modifier().style("width:100%; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12.5px;"))
                    ).modifier(new Modifier().style("flex:1;")),
                    Div.of(
                        Label.of("Relación (Edge Label):").modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                        TextField.of().id("insert_edge_label").binding("edge_label").value("MANAGES")
                            .modifier(new Modifier().style("width:100%; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12.5px;"))
                    ).modifier(new Modifier().style("flex:1;"))
                ).modifier(new Modifier().style("display:flex; gap:12px; margin-bottom:12px;")),
                JettraFluxJsonEditor.of("insert_graph_edge_props", "Propiedades de la Arista (JSON)", sampleProps)
                    .name("edge_props").height("160px")
            ).id("graph_edge_fields").modifier(new Modifier().style("display:none;"))
        );
    }

    @Override
    public GraphPayload generateSamplePayload(String database, String unit) {
        String json = "{\"name\":\"Alice Vance\",\"role\":\"Lead Architect\",\"joined\":2025}";
        JsonObject obj = JsonPayloadHelper.parseJsonOrWrap(json, "props");
        return new GraphPayload("node", "vertex_alice_01", unit != null ? unit : "Person", null, null, obj, json);
    }

    @Override
    public Map<String, String> generateSampleFormValues(String database, String unit) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("graph_mode", "node");
        map.put("target_coll", unit != null ? unit : "Person");
        map.put("target_id", "vertex_alice_01");
        map.put("node_label", unit != null ? unit : "Person");
        map.put("node_props", "{\n  \"name\": \"Alice Vance\",\n  \"role\": \"Lead Architect\",\n  \"clearance\": \"TOP_SECRET\"\n}");
        return map;
    }
}
