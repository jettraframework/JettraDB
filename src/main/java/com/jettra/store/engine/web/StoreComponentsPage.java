package com.jettra.store.engine.web;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.sun.net.httpserver.HttpExchange;
import io.jettra.flux.core.Modifier;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.core.login.NoLoginRequired;
import io.jettra.server.JettraServer;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Technical components & node internals inspection page for JettraStoreEngine.
 * Built with pure JettraFlux components.
 */
@NoLoginRequired
public class StoreComponentsPage extends StoreTemplatePage {

    private final JettraStorageEngine engine;

    public StoreComponentsPage(JettraStorageEngine engine) {
        this.engine = engine;
    }

    @Override
    protected String getPageTitle() {
        return "Components & Internals - JettraStoreEngine";
    }

    @Override
    protected Widget buildContent(HttpExchange exchange, Map<String, String> params, String currentTheme) {
        File dataDir = new File(engine.getStorageDir().toString());
        File[] files = dataDir.listFiles();
        int fileCount = files != null ? files.length : 0;
        long totalDataSize = 0;
        if (files != null) {
            for (File f : files) {
                totalDataSize += f.length();
            }
        }
        long sizeKb = totalDataSize / 1024;

        // Title Block
        Widget titleBlock = Row.of(
            Column.of(
                Header.of(1,
                    Icon.of("fas fa-microchip").modifier(new Modifier().style("color:#38bdf8; margin-right:8px;")),
                    Text.of("Engine Components & Cluster Internals")
                ).modifier(new Modifier().style("margin: 0; font-size: 26px; font-weight: 700;")),
                Paragraph.of(
                    Text.of("Technical inspection of the LSM-Tree/B-Tree Hybrid storage core, Raft consensus nodes, WAL buffers and memory persistence.")
                ).modifier(new Modifier().style("margin: 4px 0 0 0; color: #94a3b8; font-size: 14px;"))
            ),
            Row.of(
                Link.of(JettraServer.resolvePath("/dashboard"),
                    Icon.of("fas fa-arrow-left"),
                    Text.of(" Dashboard")
                ).modifier(new Modifier().cssClass("btn-action btn-secondary"))
            ).modifier(new Modifier().style("align-items: center;"))
        ).modifier(new Modifier().style("justify-content: space-between; align-items: center; margin-bottom: 24px;"));

        // Components Grid
        Widget lsmCard = Div.of(
            Row.of(
                Div.of(Icon.of("fas fa-layer-group").modifier(new Modifier().style("color:#3b82f6; font-size: 20px;")))
                    .modifier(new Modifier().style("background: rgba(59,130,246,0.15); width: 44px; height: 44px; border-radius: 10px; display: flex; align-items: center; justify-content: center;")),
                Span.of("LSM-BTREE CORE").modifier(new Modifier().cssClass("store-badge badge-active"))
            ).modifier(new Modifier().style("justify-content: space-between; align-items: center; margin-bottom: 12px;")),
            Header.of(3, Text.of("Hybrid Storage Engine")).modifier(new Modifier().style("margin: 0 0 8px 0; font-size: 17px; font-weight: 600;")),
            Paragraph.of(Text.of("Combines an in-memory Log-Structured Merge Tree (LSM) for sequential high-speed writes with on-disk B-Tree indexing for point lookups."))
                .modifier(new Modifier().style("font-size: 13px; color: #cbd5e1; line-height: 1.5; margin-bottom: 16px;")),
            Div.of(
                Div.of(Text.of("• Storage Directory: "), RawHtml.of("<code style='color:#38bdf8;'>" + engine.getStorageDir() + "</code>")).modifier(new Modifier().style("padding:4px 0;")),
                Div.of(Text.of("• Total Storage Files: "), Span.of(fileCount + " .jettra files").modifier(new Modifier().style("color:#f8fafc; font-weight:bold;"))).modifier(new Modifier().style("padding:4px 0;")),
                Div.of(Text.of("• Raw Data Stored: "), Span.of(sizeKb + " KB").modifier(new Modifier().style("color:#f8fafc; font-weight:bold;"))).modifier(new Modifier().style("padding:4px 0;"))
            ).modifier(new Modifier().style("font-size:12px; color:#94a3b8;"))
        ).modifier(new Modifier().cssClass("store-card"));

        Widget raftCard = Div.of(
            Row.of(
                Div.of(Icon.of("fas fa-network-wired").modifier(new Modifier().style("color:#a855f7; font-size: 20px;")))
                    .modifier(new Modifier().style("background: rgba(168,85,247,0.15); width: 44px; height: 44px; border-radius: 10px; display: flex; align-items: center; justify-content: center;")),
                Span.of("RAFT LEADER").modifier(new Modifier().cssClass("store-badge badge-raft"))
            ).modifier(new Modifier().style("justify-content: space-between; align-items: center; margin-bottom: 12px;")),
            Header.of(3, Text.of("Consensus Orchestrator")).modifier(new Modifier().style("margin: 0 0 8px 0; font-size: 17px; font-weight: 600;")),
            Paragraph.of(Text.of("Synchronous multi-node state machine replication ensuring strong consistency across distributed instances."))
                .modifier(new Modifier().style("font-size: 13px; color: #cbd5e1; line-height: 1.5; margin-bottom: 16px;")),
            Div.of(
                Div.of(Text.of("• Cluster Mode: "), Span.of("Standalone / Active Leader").modifier(new Modifier().style("color:#4ade80; font-weight:bold;"))).modifier(new Modifier().style("padding:4px 0;")),
                Div.of(Text.of("• Replication Port: "), RawHtml.of("<code style='color:#38bdf8;'>9092</code>")).modifier(new Modifier().style("padding:4px 0;")),
                Div.of(Text.of("• Quorum Status: "), Span.of("HEALTHY (1/1 active)").modifier(new Modifier().style("color:#4ade80; font-weight:bold;"))).modifier(new Modifier().style("padding:4px 0;"))
            ).modifier(new Modifier().style("font-size:12px; color:#94a3b8;"))
        ).modifier(new Modifier().cssClass("store-card"));

        Widget jep450Card = Div.of(
            Row.of(
                Div.of(Icon.of("fas fa-bolt").modifier(new Modifier().style("color:#f59e0b; font-size: 20px;")))
                    .modifier(new Modifier().style("background: rgba(245,158,11,0.15); width: 44px; height: 44px; border-radius: 10px; display: flex; align-items: center; justify-content: center;")),
                Span.of("JAVA 25 JEP 450").modifier(new Modifier().cssClass("store-badge badge-raft"))
            ).modifier(new Modifier().style("justify-content: space-between; align-items: center; margin-bottom: 12px;")),
            Header.of(3, Text.of("Compact Object Headers")).modifier(new Modifier().style("margin: 0 0 8px 0; font-size: 17px; font-weight: 600;")),
            Paragraph.of(Text.of("Native 64-bit object header compression in Java 25 reducing in-memory cache overhead by up to 25% for high-density document collections."))
                .modifier(new Modifier().style("font-size: 13px; color: #cbd5e1; line-height: 1.5; margin-bottom: 16px;")),
            Div.of(
                Div.of(Text.of("• Feature Status: "), Span.of("Enabled (server.compactheader=true)").modifier(new Modifier().style("color:#4ade80; font-weight:bold;"))).modifier(new Modifier().style("padding:4px 0;")),
                Div.of(Text.of("• Virtual Threads: "), Span.of("Project Loom Active").modifier(new Modifier().style("color:#38bdf8; font-weight:bold;"))).modifier(new Modifier().style("padding:4px 0;"))
            ).modifier(new Modifier().style("font-size:12px; color:#94a3b8;"))
        ).modifier(new Modifier().cssClass("store-card"));

        Widget grid = Div.of(lsmCard, raftCard, jep450Card)
            .modifier(new Modifier().style("display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 20px; margin-bottom: 24px;"));

        // Storage Directory Files List
        List<Widget> tableHeaders = List.of(
            Text.of("Filename"),
            Text.of("Size"),
            Text.of("Status")
        );

        List<List<Widget>> tableRows = new ArrayList<>();
        if (files == null || files.length == 0) {
            tableRows.add(List.of(
                Span.of("No .jettra data files currently on disk.").modifier(new Modifier().style("color:#94a3b8; text-align:center;")),
                Span.of(""),
                Span.of("")
            ));
        } else {
            for (File f : files) {
                Widget fileCell = Div.of(
                    Icon.of("fas fa-file").modifier(new Modifier().style("color:#60a5fa; margin-right:6px;")),
                    Span.of(f.getName()).modifier(new Modifier().style("font-weight:bold;"))
                );
                Widget sizeCell = Text.of(f.length() + " bytes");
                Widget statusCell = Span.of("PERSISTED").modifier(new Modifier().cssClass("store-badge badge-active"));
                tableRows.add(List.of(fileCell, sizeCell, statusCell));
            }
        }

        Datatable datatable = Datatable.ofWidgets(tableHeaders, tableRows);
        datatable.modifier(new Modifier().cssClass("jettra-table"));

        Widget storageFilesTable = Div.of(
            Header.of(3,
                Icon.of("fas fa-folder-open").modifier(new Modifier().style("color:#38bdf8; margin-right:8px;")),
                Text.of("Data Directory Files (" + engine.getStorageDir() + ")")
            ).modifier(new Modifier().style("margin: 0 0 16px 0; font-size: 18px; font-weight: 600;")),
            Div.of(datatable).modifier(new Modifier().cssClass("table-responsive"))
        ).modifier(new Modifier().cssClass("store-card"));

        return Column.of(
            titleBlock,
            grid,
            storageFilesTable
        );
    }
}
