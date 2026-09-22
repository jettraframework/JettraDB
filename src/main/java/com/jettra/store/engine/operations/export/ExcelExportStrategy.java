package com.jettra.store.engine.operations.export;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Strategy implementation for Excel (.xls / Spreadsheet XML) formatted export.
 */
public final class ExcelExportStrategy implements ExportStrategy {

    @Override
    public String format() {
        return "excel";
    }

    @Override
    public String displayName() {
        return "Excel (.xls) - Spreadsheet Workbook Table";
    }

    @Override
    public String mimeType() {
        return "application/vnd.ms-excel; charset=UTF-8";
    }

    @Override
    public String fileExtension() {
        return "xls";
    }

    @Override
    public byte[] export(String database, String engineFilter, String collectionFilter, Map<String, String> recordsMap) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html xmlns:o=\"urn:schemas-microsoft-com:office:office\" xmlns:x=\"urn:schemas-microsoft-com:office:excel\" xmlns=\"http://www.w3.org/TR/REC-html40\">");
        sb.append("<head><meta charset=\"utf-8\"/><!--[if gte mso 9]><xml><x:ExcelWorkbook><x:ExcelWorksheets><x:ExcelWorksheet><x:Name>Export</x:Name><x:WorksheetOptions><x:DisplayGridlines/></x:WorksheetOptions></x:ExcelWorksheet></x:ExcelWorksheets></x:ExcelWorkbook></xml><![endif]--></head>");
        sb.append("<body><table border=\"1\" style=\"border-collapse:collapse; font-family:Arial,sans-serif; font-size:12px;\">");
        sb.append("<tr style=\"background:#1e293b; color:#38bdf8; font-weight:bold; height:30px;\"><th>Storage Key</th><th>Database</th><th>Unit / Collection</th><th>Record ID</th><th>Payload JSON / Content</th></tr>");
        if (recordsMap != null) {
            for (Map.Entry<String, String> entry : recordsMap.entrySet()) {
                String k = entry.getKey();
                String val = entry.getValue() != null ? entry.getValue() : "";
                String[] parts = k.split(":");
                String unit = parts.length > 2 ? parts[2] : (parts.length > 1 ? parts[1] : "default");
                String id = parts.length > 0 ? parts[parts.length - 1] : k;
                sb.append("<tr>")
                  .append("<td style=\"font-weight:bold; color:#0f172a;\">").append(escapeXml(k)).append("</td>")
                  .append("<td>").append(escapeXml(database)).append("</td>")
                  .append("<td>").append(escapeXml(unit)).append("</td>")
                  .append("<td style=\"font-family:monospace;\">").append(escapeXml(id)).append("</td>")
                  .append("<td style=\"font-family:monospace;\">").append(escapeXml(val)).append("</td>")
                  .append("</tr>");
            }
        }
        sb.append("</table></body></html>");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
