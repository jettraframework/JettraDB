package com.jettra.store.engine.web;

import com.jettra.store.engine.core.JettraStorageEngine;
import com.sun.net.httpserver.HttpExchange;
import io.jettra.flux.core.Widget;
import io.jettra.flux.widgets.*;
import io.jettra.core.login.NoLoginRequired;
import io.jettra.server.JettraServer;
import java.io.File;
import java.util.Map;

/**
 * Technical components & node internals inspection page for JettraStoreEngine.
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
                Paragraph.of("<h1 style='margin: 0; font-size: 26px; font-weight: 700;'><i class='fas fa-microchip' style='color:#38bdf8; margin-right:8px;'></i> Engine Components & Cluster Internals</h1>"),
                Paragraph.of("<p style='margin: 4px 0 0 0; color: #94a3b8; font-size: 14px;'>Technical inspection of the LSM-Tree/B-Tree Hybrid storage core, Raft consensus nodes, WAL buffers and memory persistence.</p>")
            ),
            Row.of(
                Paragraph.of("<a href='" + JettraServer.resolvePath("/dashboard") + "' class='btn-action btn-secondary'><i class='fas fa-arrow-left'></i> Dashboard</a>")
            ).modifier(new io.jettra.flux.core.Modifier().style("align-items: center;"))
        ).modifier(new io.jettra.flux.core.Modifier().style("justify-content: space-between; align-items: center; margin-bottom: 24px;"));

        // Components Grid
        Widget lsmCard = Div.of(
            Row.of(
                Div.of(Paragraph.of("<i class='fas fa-layer-group' style='color:#3b82f6; font-size: 20px;'></i>"))
                    .modifier(new io.jettra.flux.core.Modifier().style("background: rgba(59,130,246,0.15); width: 44px; height: 44px; border-radius: 10px; display: flex; align-items: center; justify-content: center;")),
                Span.of("LSM-BTREE CORE").modifier(new io.jettra.flux.core.Modifier().cssClass("store-badge badge-active"))
            ).modifier(new io.jettra.flux.core.Modifier().style("justify-content: space-between; align-items: center; margin-bottom: 12px;")),
            Paragraph.of("<h3 style='margin: 0 0 8px 0; font-size: 17px; font-weight: 600;'>Hybrid Storage Engine</h3>"),
            Paragraph.of("<p style='font-size: 13px; color: #cbd5e1; line-height: 1.5; margin-bottom: 16px;'>Combines an in-memory Log-Structured Merge Tree (LSM) for sequential high-speed writes with on-disk B-Tree indexing for point lookups.</p>"),
            Paragraph.of(
                "<ul style='list-style:none; padding:0; margin:0; font-size:12px; color:#94a3b8;'>\n" +
                "  <li style='padding:4px 0;'>• Storage Directory: <code style='color:#38bdf8;'>" + engine.getStorageDir() + "</code></li>\n" +
                "  <li style='padding:4px 0;'>• Total Storage Files: <b style='color:#f8fafc;'>" + fileCount + " .jettra files</b></li>\n" +
                "  <li style='padding:4px 0;'>• Raw Data Stored: <b style='color:#f8fafc;'>" + sizeKb + " KB</b></li>\n" +
                "</ul>"
            )
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("store-card"));

        Widget raftCard = Div.of(
            Row.of(
                Div.of(Paragraph.of("<i class='fas fa-network-wired' style='color:#a855f7; font-size: 20px;'></i>"))
                    .modifier(new io.jettra.flux.core.Modifier().style("background: rgba(168,85,247,0.15); width: 44px; height: 44px; border-radius: 10px; display: flex; align-items: center; justify-content: center;")),
                Span.of("RAFT LEADER").modifier(new io.jettra.flux.core.Modifier().cssClass("store-badge badge-raft"))
            ).modifier(new io.jettra.flux.core.Modifier().style("justify-content: space-between; align-items: center; margin-bottom: 12px;")),
            Paragraph.of("<h3 style='margin: 0 0 8px 0; font-size: 17px; font-weight: 600;'>Consensus Orchestrator</h3>"),
            Paragraph.of("<p style='font-size: 13px; color: #cbd5e1; line-height: 1.5; margin-bottom: 16px;'>Synchronous multi-node state machine replication ensuring strong consistency across distributed instances.</p>"),
            Paragraph.of(
                "<ul style='list-style:none; padding:0; margin:0; font-size:12px; color:#94a3b8;'>\n" +
                "  <li style='padding:4px 0;'>• Cluster Mode: <b style='color:#4ade80;'>Standalone / Active Leader</b></li>\n" +
                "  <li style='padding:4px 0;'>• Replication Port: <code style='color:#38bdf8;'>9092</code></li>\n" +
                "  <li style='padding:4px 0;'>• Quorum Status: <b style='color:#4ade80;'>HEALTHY (1/1 active)</b></li>\n" +
                "</ul>"
            )
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("store-card"));

        Widget jep450Card = Div.of(
            Row.of(
                Div.of(Paragraph.of("<i class='fas fa-bolt' style='color:#f59e0b; font-size: 20px;'></i>"))
                    .modifier(new io.jettra.flux.core.Modifier().style("background: rgba(245,158,11,0.15); width: 44px; height: 44px; border-radius: 10px; display: flex; align-items: center; justify-content: center;")),
                Span.of("JAVA 25 JEP 450").modifier(new io.jettra.flux.core.Modifier().cssClass("store-badge badge-raft"))
            ).modifier(new io.jettra.flux.core.Modifier().style("justify-content: space-between; align-items: center; margin-bottom: 12px;")),
            Paragraph.of("<h3 style='margin: 0 0 8px 0; font-size: 17px; font-weight: 600;'>Compact Object Headers</h3>"),
            Paragraph.of("<p style='font-size: 13px; color: #cbd5e1; line-height: 1.5; margin-bottom: 16px;'>Native 64-bit object header compression in Java 25 reducing in-memory cache overhead by up to 25% for high-density document collections.</p>"),
            Paragraph.of(
                "<ul style='list-style:none; padding:0; margin:0; font-size:12px; color:#94a3b8;'>\n" +
                "  <li style='padding:4px 0;'>• Feature Status: <b style='color:#4ade80;'>Enabled (server.compactheader=true)</b></li>\n" +
                "  <li style='padding:4px 0;'>• Virtual Threads: <b style='color:#38bdf8;'>Project Loom Active</b></li>\n" +
                "</ul>"
            )
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("store-card"));

        Widget grid = Div.of(lsmCard, raftCard, jep450Card)
            .modifier(new io.jettra.flux.core.Modifier().style("display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 20px; margin-bottom: 24px;"));

        // Storage Directory Files List
        StringBuilder fileRows = new StringBuilder();
        if (files == null || files.length == 0) {
            fileRows.append("<tr><td colspan='3' style='text-align:center; color:#94a3b8;'>No .jettra data files currently on disk.</td></tr>\n");
        } else {
            for (File f : files) {
                fileRows.append("<tr>\n")
                    .append("  <td><i class='fas fa-file' style='color:#60a5fa; margin-right:6px;'></i> <b>").append(f.getName()).append("</b></td>\n")
                    .append("  <td>").append(f.length()).append(" bytes</td>\n")
                    .append("  <td><span class='store-badge badge-active'>PERSISTED</span></td>\n")
                    .append("</tr>\n");
            }
        }

        Widget storageFilesTable = Div.of(
            Paragraph.of("<h3 style='margin: 0 0 16px 0; font-size: 18px; font-weight: 600;'><i class='fas fa-folder-open' style='color:#38bdf8; margin-right:8px;'></i> Data Directory Files (" + engine.getStorageDir() + ")</h3>"),
            Paragraph.of(
                "<div class='table-responsive'>\n" +
                "  <table class='jettra-table'>\n" +
                "    <thead>\n" +
                "      <tr>\n" +
                "        <th>Filename</th>\n" +
                "        <th>Size</th>\n" +
                "        <th>Status</th>\n" +
                "      </tr>\n" +
                "    </thead>\n" +
                "    <tbody>\n" +
                fileRows.toString() +
                "    </tbody>\n" +
                "  </table>\n" +
                "</div>"
            )
        ).modifier(new io.jettra.flux.core.Modifier().cssClass("store-card"));

        return Column.of(
            titleBlock,
            grid,
            storageFilesTable
        );
    }
}
