package com.jettra.store.engine.insertion.strategies;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.jettra.store.engine.insertion.EngineRecordInsertionStrategy;
import com.jettra.store.engine.insertion.EngineRecordPayload.SpatialGeoPayload;
import com.jettra.store.engine.insertion.EngineType;
import com.jettra.store.engine.insertion.InsertionResult;
import com.jettra.store.engine.insertion.JsonPayloadHelper;
import com.jettra.store.engine.insertion.ValidationResult;
import com.jettra.store.engine.models.GeospatialEngine;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.json.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strategy implementation for Geospatial Coordinates & Spatial GIS layers engine.
 */
public class SpatialGeoInsertionStrategy implements EngineRecordInsertionStrategy<SpatialGeoPayload> {

    @Override
    public EngineType engineType() {
        return new EngineType.SpatialGeo();
    }

    @Override
    public ValidationResult validate(Map<String, String> params) {
        List<String> errors = new ArrayList<>();
        String layer = params.get("target_coll");
        if (layer == null || layer.isBlank()) layer = params.get("geo_layer");
        if (layer == null || layer.isBlank()) {
            errors.add("La capa espacial ('Spatial Layer') es requerida.");
        }
        String latStr = params.get("geo_lat");
        if (latStr == null || latStr.isBlank()) {
            errors.add("La latitud ('Latitude') es requerida.");
        } else {
            try {
                double lat = Double.parseDouble(latStr.trim());
                if (lat < -90.0 || lat > 90.0) errors.add("La latitud debe estar entre -90.0 y +90.0.");
            } catch (NumberFormatException e) {
                errors.add("Latitud no válida: ingrese un número decimal.");
            }
        }
        String lonStr = params.get("geo_lon");
        if (lonStr == null || lonStr.isBlank()) {
            errors.add("La longitud ('Longitude') es requerida.");
        } else {
            try {
                double lon = Double.parseDouble(lonStr.trim());
                if (lon < -180.0 || lon > 180.0) errors.add("La longitud debe estar entre -180.0 y +180.0.");
            } catch (NumberFormatException e) {
                errors.add("Longitud no válida: ingrese un número decimal.");
            }
        }
        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(errors);
    }

    @Override
    public SpatialGeoPayload parsePayload(String database, String unit, String id, Map<String, String> params) {
        String layer = (unit != null && !unit.isBlank()) ? unit : params.getOrDefault("target_coll", "logistics_pois");
        String featId = (id != null && !id.isBlank()) ? id : params.getOrDefault("target_id", "poi_hub_01");
        String name = params.getOrDefault("geo_name", featId);
        String geomType = params.getOrDefault("geo_type", "Point");
        double lat = 8.9824;
        double lon = -79.5199;
        try { lat = Double.parseDouble(params.getOrDefault("geo_lat", "8.9824").trim()); } catch (Exception ignored) {}
        try { lon = Double.parseDouble(params.getOrDefault("geo_lon", "-79.5199").trim()); } catch (Exception ignored) {}

        String rawMeta = params.getOrDefault("geo_meta", "{}");
        JsonObject meta = JsonPayloadHelper.parseJsonOrWrap(rawMeta, "properties");
        meta.addProperty("name", name);
        meta.addProperty("geometryType", geomType);
        meta.addProperty("latitude", lat);
        meta.addProperty("longitude", lon);
        meta.addProperty("_layer", layer);

        return new SpatialGeoPayload(featId, layer, name, geomType, lat, lon, meta, rawMeta);
    }

    @Override
    public InsertionResult executeInsert(JettraStorageEngine storageEngine, String database, SpatialGeoPayload payload) {
        GeospatialEngine geoEngine = (GeospatialEngine) storageEngine.getEngine("GEOSPATIAL");
        if (geoEngine == null) {
            return InsertionResult.ofError("GEOSPATIAL", database, payload.layer(), payload.id(), "GeospatialEngine not registered in storage orchestrator");
        }
        geoEngine.insertLocation(database, payload.id(), payload.latitude(), payload.longitude(), payload.properties());
        return InsertionResult.ofSuccess("GEOSPATIAL", database, payload.layer(), payload.id(),
                "Geo Feature '" + payload.id() + "' [" + payload.latitude() + ", " + payload.longitude() + "] saved into layer [" + payload.layer() + "]", 1);
    }

    @Override
    public Widget buildEngineFormFields(String currentDb, String currentUnit) {
        String sampleGeoJson = "{\n  \"category\": \"LOGISTICS_HUB\",\n  \"status\": \"OPERATIONAL\",\n  \"altitude_m\": 18.5,\n  \"city\": \"Panama City\",\n  \"country\": \"PA\"\n}";

        return Div.of(
            Div.of(
                Div.of(
                    Label.of("Capa Espacial (Spatial Layer):").modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("insert_geo_layer").binding("target_coll").value(currentUnit != null ? currentUnit : "facilities_layer")
                        .modifier(new Modifier().style("width:100%; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12.5px;"))
                ).modifier(new Modifier().style("flex:1;")),
                Div.of(
                    Label.of("Nombre de Feature / Lugar:").modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("insert_geo_name").binding("geo_name").value("Centro Logístico Pacífica")
                        .modifier(new Modifier().style("width:100%; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12.5px;"))
                ).modifier(new Modifier().style("flex:1;")),
                Div.of(
                    Label.of("Tipo de Geometría:").modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    RawHtml.of("<select name=\"geo_type\" id=\"insert_geo_type\" style=\"width:100%; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:var(--j-text-primary); font-size:12.5px;\">" +
                            "<option value=\"Point\" selected>Point (Coordenada)</option>" +
                            "<option value=\"Polygon\">Polygon (Polígono)</option>" +
                            "<option value=\"MultiPolygon\">MultiPolygon</option>" +
                            "<option value=\"LineString\">LineString (Trayectoria)</option></select>")
                ).modifier(new Modifier().style("flex:1;"))
            ).modifier(new Modifier().style("display:flex; gap:12px; margin-bottom:12px;")),

            Div.of(
                Div.of(
                    Label.of("Latitud (-90.0 a +90.0):").modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("insert_geo_lat").binding("geo_lat").value("8.9824")
                        .modifier(new Modifier().style("width:100%; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:#14b8a6; font-weight:600; font-size:12.5px;"))
                ).modifier(new Modifier().style("flex:1;")),
                Div.of(
                    Label.of("Longitud (-180.0 a +180.0):").modifier(new Modifier().style("font-size:11.5px; font-weight:600; color:var(--j-text-secondary); margin-bottom:4px; display:block;")),
                    TextField.of().id("insert_geo_lon").binding("geo_lon").value("-79.5199")
                        .modifier(new Modifier().style("width:100%; padding:8px 12px; background:var(--j-bg-body); border:1px solid var(--j-border); border-radius:6px; color:#14b8a6; font-weight:600; font-size:12.5px;"))
                ).modifier(new Modifier().style("flex:1;"))
            ).modifier(new Modifier().style("display:flex; gap:12px; margin-bottom:12px;")),

            Div.of(
                JettraFluxJsonEditor.of("insert_geo_meta", "Propiedades y Coordenadas GeoJSON (JSON)", sampleGeoJson)
                    .name("geo_meta").height("150px")
            )
        );
    }

    @Override
    public SpatialGeoPayload generateSamplePayload(String database, String unit) {
        JsonObject props = new JsonObject();
        props.addProperty("zone", "Bay Area");
        props.addProperty("capacity", 250);
        return new SpatialGeoPayload("geo_hub_pacifica", unit != null ? unit : "facilities", "Pacífica Logistics Hub", "Point", 8.9824, -79.5199, props, props.toString());
    }

    @Override
    public Map<String, String> generateSampleFormValues(String database, String unit) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("target_coll", unit != null ? unit : "facilities_layer");
        map.put("target_id", "geo_facility_01");
        map.put("geo_name", "Pacific Port Facility Hub");
        map.put("geo_type", "Point");
        map.put("geo_lat", "8.9824");
        map.put("geo_lon", "-79.5199");
        map.put("geo_meta", "{\n  \"capacity\": 50000,\n  \"carrier\": \"Jettra Maritime\",\n  \"status\": \"ACTIVE\"\n}");
        return map;
    }
}
