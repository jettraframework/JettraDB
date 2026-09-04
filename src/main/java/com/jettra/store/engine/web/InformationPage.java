package com.jettra.store.engine.web;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.sun.net.httpserver.HttpExchange;
import jcf.annotation.PageWidgetAllow;
import jcf.AppRole;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.server.JettraServer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Information Page for JettraStoreEngine.
 * Displays comprehensive architecture, storage schemas, and capabilities of all 9 multi-model database engines.
 */
@PageWidgetAllow(role = { jcf.AppRole.ADMIN, jcf.AppRole.MANAGER, jcf.AppRole.USER })
public class InformationPage extends StoreTemplatePage {

    private final JettraStorageEngine engine;

    public InformationPage(JettraStorageEngine engine) {
        this.engine = engine;
    }

    @Override
    protected String getPageTitle() {
        return "Multi-Model Engines Information - JettraStoreEngine";
    }

    @Override
    protected Widget buildContent(HttpExchange exchange, Map<String, String> params, String currentTheme) {
        // Title Header Block
        Widget titleBlock = Row.of(
            Column.of(
                Header.of(1,
                    Icon.of("fas fa-info-circle").modifier(new Modifier().style("color:#38bdf8; margin-right:8px;")),
                    Text.of("Multi-Model Engines Information")
                ).modifier(new Modifier().style("margin: 0; font-size: 26px; font-weight: 700;")),
                Paragraph.of(
                    Text.of("Comprehensive architectural matrix, storage models, and replication capabilities for all 9 native multi-model engines.")
                ).modifier(new Modifier().style("margin: 4px 0 0 0; color: #94a3b8; font-size: 14px;"))
            )
        ).modifier(new Modifier().style("justify-content: space-between; align-items: center; margin-bottom: 24px;"));
//        Widget titleBlock = Row.of(
//            Column.of(
//                Header.of(1,
//                    Icon.of("fas fa-info-circle").modifier(new Modifier().style("color:#38bdf8; margin-right:8px;")),
//                    Text.of("Multi-Model Engines Information")
//                ).modifier(new Modifier().style("margin: 0; font-size: 26px; font-weight: 700;")),
//                Paragraph.of(
//                    Text.of("Comprehensive architectural matrix, storage models, and replication capabilities for all 9 native multi-model engines.")
//                ).modifier(new Modifier().style("margin: 4px 0 0 0; color: #94a3b8; font-size: 14px;"))
//            ),
//            Row.of(
//                Link.of(JettraServer.resolvePath("/engines"),
//                    Icon.of("fas fa-cubes"),
//                    Text.of(" Engines Explorer")
//                ).modifier(new Modifier().cssClass("btn-action btn-secondary").style("margin-right:8px;")),
//                Link.of(JettraServer.resolvePath("/dashboard"),
//                    Icon.of("fas fa-tachometer-alt"),
//                    Text.of(" Dashboard")
//                ).modifier(new Modifier().cssClass("btn-action btn-primary"))
//            ).modifier(new Modifier().style("align-items: center;"))
//        ).modifier(new Modifier().style("justify-content: space-between; align-items: center; margin-bottom: 24px;"));

        // Overview Highlights
        Widget highlightsGrid = Row.of(
            Div.of(
                Header.of(4, Icon.of("fas fa-layer-group"), Text.of(" 9 Native Storage Models")).modifier(new Modifier().style("color:#38bdf8; margin:0 0 6px 0; font-size:15px; font-weight:700;")),
                Paragraph.of(Text.of("Unified LSM/B-Tree hybrid storage engine supporting Document, KeyValue, Vector, Graph, TimeSeries, Column, Geospatial, Object BLOB, and Java 25 Immutable Records."))
                    .modifier(new Modifier().style("font-size:12px; color:#cbd5e1; margin:0; line-height:1.5;"))
            ).modifier(new Modifier().cssClass("store-card").style("flex:1; padding:16px; border-left:4px solid #38bdf8;")),
            Div.of(
                Header.of(4, Icon.of("fas fa-network-wired"), Text.of(" Raft Cluster Consensus")).modifier(new Modifier().style("color:#a855f7; margin:0 0 6px 0; font-size:15px; font-weight:700;")),
                Paragraph.of(Text.of("Synchronous multi-node log replication and distributed Raft election ensuring strong durability and linearizable state machines."))
                    .modifier(new Modifier().style("font-size:12px; color:#cbd5e1; margin:0; line-height:1.5;"))
            ).modifier(new Modifier().cssClass("store-card").style("flex:1; padding:16px; border-left:4px solid #a855f7;")),
            Div.of(
                Header.of(4, Icon.of("fas fa-bolt"), Text.of(" Java 25 High Throughput")).modifier(new Modifier().style("color:#f43f5e; margin:0 0 6px 0; font-size:15px; font-weight:700;")),
                Paragraph.of(Text.of("Optimized for modern OpenJDK with virtual threads, direct memory-mapped SSTables, vector math similarity, and compact record binary encoding."))
                    .modifier(new Modifier().style("font-size:12px; color:#cbd5e1; margin:0; line-height:1.5;"))
            ).modifier(new Modifier().cssClass("store-card").style("flex:1; padding:16px; border-left:4px solid #f43f5e;"))
        ).modifier(new Modifier().style("display:flex; gap:16px; margin-bottom:24px; flex-wrap:wrap;"));

        // All 9 Supported Multi-Model Engines Table Matrix
        Widget engineMatrixTable = createEngineMatrixTable();

        return Column.of(
            titleBlock,
            highlightsGrid,
            engineMatrixTable
        );
    }

    private Widget createEngineMatrixTable() {
        Widget header = Header.of(3,
            Icon.of("fas fa-table").modifier(new Modifier().style("color:#38bdf8; margin-right:8px;")),
            Text.of("All 9 Supported Multi-Model Engines")
        ).modifier(new Modifier().style("margin: 0 0 16px 0; font-size: 18px; font-weight: 600;"));

        List<Widget> headers = List.of(
            Text.of("Engine Name"),
            Text.of("Primary Use Case"),
            Text.of("Storage Schema"),
            Text.of("Replication"),
            Text.of("REST API Route"),
            Text.of("Status")
        );

        String[][] matrixData = {
            {"fas fa-file-alt", "#3b82f6", "DOCUMENT", "Hierarchical JSON / NoSQL documents", "B-Tree / LSM Hybrid", "Raft Sync", "/api/document/{coll}/{id}", "ACTIVE"},
            {"fas fa-key", "#10b981", "KEYVALUE", "Session Cache, Distributed Key-Value", "LSM MemTable + SSTable", "Raft Sync", "/api/model/keyvalue/*", "ACTIVE"},
            {"fas fa-project-diagram", "#8b5cf6", "VECTOR", "AI Embeddings, Cosine Similarity, ANN", "Vector Index (float[])", "Raft Sync", "/api/model/vector/*", "ACTIVE"},
            {"fas fa-share-alt", "#ec4899", "GRAPH", "Knowledge Graphs, Social Networks, Traversal", "Adjacency List + B-Tree", "Raft Sync", "/api/model/graph/*", "ACTIVE"},
            {"fas fa-chart-line", "#06b6d4", "TIMESERIES", "IoT Telemetry, Metrics, Server Logs", "Append-only Chunked WAL", "Raft Sync", "/api/model/timeseries/*", "ACTIVE"},
            {"fas fa-table", "#f97316", "COLUMN", "OLAP Big Data Aggregations", "Column Vectors & Run-Length", "Raft Sync", "/api/model/column/*", "ACTIVE"},
            {"fas fa-globe-americas", "#14b8a6", "GEOSPATIAL", "Spatial Coordinates, Radius, GIS", "Geohash / QuadTree", "Raft Sync", "/api/model/geospatial/*", "ACTIVE"},
            {"fas fa-archive", "#a855f7", "OBJECT", "Binary BLOBs, Serialized Stream Files", "Chunked Block Store", "Raft Sync", "/api/model/object/*", "ACTIVE"},
            {"fas fa-id-card", "#f43f5e", "RECORDS", "Immutable Java 25 Records, Component Validation", "Compact Object Headers (rec:)", "Raft Sync", "/api/model/records/*", "ACTIVE"}
        };

        List<List<Widget>> rows = new ArrayList<>();
        for (String[] rowData : matrixData) {
            Widget nameCell = Div.of(
                Icon.of(rowData[0]).modifier(new Modifier().style("color:" + rowData[1] + "; margin-right:6px;")),
                Span.of(rowData[2]).modifier(new Modifier().style("font-weight:bold;"))
            );
            Widget useCaseCell = Text.of(rowData[3]);
            Widget schemaCell = Text.of(rowData[4]);
            Widget replCell = Text.of(rowData[5]);
            Widget routeCell = RawHtml.of("<code>" + rowData[6] + "</code>");
            Widget statusCell = Span.of(rowData[7]).modifier(new Modifier().cssClass("store-badge badge-active"));

            rows.add(List.of(nameCell, useCaseCell, schemaCell, replCell, routeCell, statusCell));
        }

        Datatable datatable = Datatable.ofWidgets(headers, rows);
        datatable.modifier(new Modifier().cssClass("jettra-table"));

        Widget tableResponsive = Div.of(datatable).modifier(new Modifier().cssClass("table-responsive"));

        return Div.of(header, tableResponsive).modifier(new Modifier().cssClass("store-card"));
    }
}
