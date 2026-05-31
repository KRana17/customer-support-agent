package com.example.support.util;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * One-time utility to generate {@code mock-data.xlsx} in {@code src/main/resources/}.
 *
 * <p>Run this class once when setting up the project for the first time, or whenever
 * you want to reset the mock data to its defaults:
 *
 * <pre>
 *   mvn compile exec:java -Dexec.mainClass=com.example.support.util.MockDataGenerator
 * </pre>
 *
 * <p>After generation, you can open the file in Excel/LibreOffice to add your own
 * test scenarios without touching any Java code.
 */
public class MockDataGenerator {

    public static void main(String[] args) throws Exception {
        Path outputPath = Paths.get("src/main/resources/mock-data.xlsx");

        try (Workbook wb = new XSSFWorkbook();
             FileOutputStream out = new FileOutputStream(outputPath.toFile())) {

            createOrdersSheet(wb);
            createKnowledgeBaseSheet(wb);
            createDiagnosticsSheet(wb);

            wb.write(out);
        }

        System.out.println("✅ mock-data.xlsx written to: " + outputPath.toAbsolutePath());
        System.out.println("   Open it in Excel/LibreOffice to add your own test scenarios.");
    }

    // ── Sheet builders ─────────────────────────────────────────────────────────

    private static void createOrdersSheet(Workbook wb) {
        Sheet sheet = wb.createSheet("Orders");

        // Header row
        row(sheet, 0, "order_id", "customer_id", "status", "tracking_number",
                "estimated_delivery", "total_amount", "refund_eligible");

        // Data rows — ORD-77291 and ORD-55100 match the demo scenarios in SupportAgentRunner
        row(sheet, 1, "ORD-77291", "CUST-002", "SHIPPED",    "TRK-88291-CA", "2026-06-05", "149.99", "true");
        row(sheet, 2, "ORD-55100", "CUST-003", "DELIVERED",  "TRK-55100-XY", "2026-05-20",  "89.50", "false");
        row(sheet, 3, "ORD-10001", "CUST-001", "PROCESSING", "N/A",          "2026-06-10",  "49.99", "false");
        row(sheet, 4, "ORD-20020", "CUST-004", "DELIVERED",  "TRK-20020-ZA", "2026-05-15", "220.00", "true");
        row(sheet, 5, "ORD-30030", "CUST-005", "CANCELLED",  "N/A",          "N/A",          "75.00", "false");

        autoSizeColumns(sheet, 7);
    }

    private static void createKnowledgeBaseSheet(Workbook wb) {
        Sheet sheet = wb.createSheet("KnowledgeBase");

        // Header row
        row(sheet, 0, "topic", "keywords", "answer", "source_url", "confidence");

        // Knowledge base entries — keywords are comma-separated and drive the search scoring
        row(sheet, 1,
                "return-policy",
                "return,refund,policy,days,how many,can i return",
                "Items can be returned within 30 days of delivery for a full refund. " +
                "Items must be unused and in original packaging. " +
                "Digital products and perishables are non-refundable.",
                "https://help.yourstore.com/returns",
                "0.95");

        row(sheet, 2,
                "shipping-times",
                "shipping,delivery,how long,when,arrive,standard,express",
                "Standard shipping takes 5-7 business days. " +
                "Express shipping takes 1-2 business days. " +
                "Free standard shipping on orders over $50.",
                "https://help.yourstore.com/shipping",
                "0.92");

        row(sheet, 3,
                "payment-methods",
                "payment,pay,credit,card,method,accept,visa,mastercard,paypal",
                "We accept Visa, Mastercard, American Express, PayPal, and Apple Pay. " +
                "All transactions are encrypted with TLS 1.3.",
                "https://help.yourstore.com/payment",
                "0.90");

        row(sheet, 4,
                "product-warranty",
                "warranty,guarantee,broken,defective,damaged,replace",
                "All products carry a 1-year manufacturer warranty. " +
                "For defective items received, contact us within 30 days for a free replacement or full refund.",
                "https://help.yourstore.com/warranty",
                "0.88");

        row(sheet, 5,
                "account-creation",
                "account,sign up,register,create,join,membership",
                "Create a free account at yourstore.com/signup using your email. " +
                "You can also sign up with Google or Apple. No fees or subscriptions required.",
                "https://help.yourstore.com/account",
                "0.85");

        row(sheet, 6,
                "order-cancellation",
                "cancel,cancellation,cancel order,stop order,undo order",
                "Orders can be cancelled within 1 hour of placement. " +
                "After that, contact support and we will attempt to intercept the shipment. " +
                "Once shipped, use the return process.",
                "https://help.yourstore.com/cancel",
                "0.87");

        autoSizeColumns(sheet, 5);
    }

    private static void createDiagnosticsSheet(Workbook wb) {
        Sheet sheet = wb.createSheet("Diagnostics");

        // Header row
        row(sheet, 0, "user_id", "account_status", "last_login",
                "connectivity_test", "known_incidents", "incident_description");

        // CUST-003 has a known incident — matches Demo 3 in SupportAgentRunner
        row(sheet, 1, "CUST-001", "ACTIVE",    "2026-05-29T10:00:00Z", "PASS", "false", "");
        row(sheet, 2, "CUST-002", "ACTIVE",    "2026-05-28T18:45:00Z", "PASS", "false", "");
        row(sheet, 3, "CUST-003", "ACTIVE",    "2026-05-27T09:12:00Z", "FAIL", "true",
                "App crash on checkout screen — mobile app v2.4.1 known issue, fix in v2.4.2 (ETA 2026-06-01)");
        row(sheet, 4, "CUST-004", "ACTIVE",    "2026-05-30T08:00:00Z", "PASS", "false", "");
        row(sheet, 5, "CUST-005", "SUSPENDED", "2026-04-10T12:30:00Z", "FAIL", "true",
                "Account suspended due to chargeback dispute. Connectivity blocked pending review.");

        autoSizeColumns(sheet, 6);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static void row(Sheet sheet, int rowNum, String... values) {
        Row row = sheet.createRow(rowNum);
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(values[i]);
        }
    }

    private static void autoSizeColumns(Sheet sheet, int count) {
        for (int i = 0; i < count; i++) {
            sheet.autoSizeColumn(i);
        }
    }
}
