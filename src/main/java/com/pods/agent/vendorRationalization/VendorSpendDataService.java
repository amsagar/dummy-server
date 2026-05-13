package com.pods.agent.vendorRationalization;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Loads vendor spend data dynamically from the Excel file at
 * {@code classpath:vendor-rationalization/vendor-spend-2025.xlsx}.
 *
 * <p>The file is re-read on every call to {@link #reload()} so operators
 * can drop a new file in place and call the reload endpoint without
 * restarting the server. The in-memory snapshot is replaced atomically.
 *
 * <p>Sheets consumed:
 * <ul>
 *   <li><b>Enriched Data</b> — 2,224 vendor rows with spend, category, etc.</li>
 *   <li><b>Pareto Analysis</b> — pre-ranked vendor list with cumulative %</li>
 *   <li><b>Pivot Table</b> — category-level aggregates</li>
 * </ul>
 */
@Service
@Slf4j
public class VendorSpendDataService {

    private static final String EXCEL_PATH = "vendor-rationalization/vendor-spend-2025.xlsx";

    private final AtomicReference<DataSnapshot> snapshot = new AtomicReference<>(DataSnapshot.EMPTY);

    @PostConstruct
    public void init() {
        reload();
    }

    /** Re-reads the Excel file and replaces the in-memory snapshot. */
    public synchronized void reload() {
        try {
            ClassPathResource resource = new ClassPathResource(EXCEL_PATH);
            if (!resource.exists()) {
                log.warn("[VendorSpendDataService] Excel file not found at classpath:{}", EXCEL_PATH);
                return;
            }
            try (InputStream is = resource.getInputStream();
                 Workbook wb = new XSSFWorkbook(is)) {

                List<VendorRationalizationDtos.VendorRow> enriched = readEnrichedData(wb);
                List<VendorRationalizationDtos.ParetoRow> pareto = readParetoData(wb);

                snapshot.set(new DataSnapshot(enriched, pareto));
                log.info("[VendorSpendDataService] Loaded {} vendor rows, {} pareto rows",
                        enriched.size(), pareto.size());
            }
        } catch (Exception e) {
            log.error("[VendorSpendDataService] Failed to load Excel file: {}", e.getMessage(), e);
        }
    }

    public List<VendorRationalizationDtos.VendorRow> getAllVendors() {
        return snapshot.get().enrichedData();
    }

    public List<VendorRationalizationDtos.ParetoRow> getParetoData() {
        return snapshot.get().paretoData();
    }

    // ── Sheet readers ─────────────────────────────────────────────────────────

    private List<VendorRationalizationDtos.VendorRow> readEnrichedData(Workbook wb) {
        Sheet sheet = wb.getSheet("Enriched Data");
        if (sheet == null) {
            log.warn("[VendorSpendDataService] 'Enriched Data' sheet not found");
            return List.of();
        }

        List<VendorRationalizationDtos.VendorRow> rows = new ArrayList<>();
        boolean firstRow = true;
        for (Row row : sheet) {
            if (firstRow) { firstRow = false; continue; } // skip header
            if (isRowEmpty(row)) continue;

            try {
                String accountNum = cellStr(row, 0);
                String topGroup   = cellStr(row, 1);
                String name       = cellStr(row, 2);
                String vendorGroup = cellStr(row, 3);
                String address    = cellStr(row, 4);
                String currency   = cellStr(row, 5);
                double amount     = cellDouble(row, 6);
                String genCat     = cellStr(row, 7);
                String cat        = cellStr(row, 8);
                String addlSvc    = cellStr(row, 9);

                if (name == null || name.isBlank()) continue;

                rows.add(new VendorRationalizationDtos.VendorRow(
                        accountNum, topGroup, name, vendorGroup,
                        address, currency, amount, genCat, cat, addlSvc));
            } catch (Exception e) {
                log.debug("[VendorSpendDataService] Skipping row {}: {}", row.getRowNum(), e.getMessage());
            }
        }
        return Collections.unmodifiableList(rows);
    }

    private List<VendorRationalizationDtos.ParetoRow> readParetoData(Workbook wb) {
        Sheet sheet = wb.getSheet("Pareto Analysis");
        if (sheet == null) {
            log.warn("[VendorSpendDataService] 'Pareto Analysis' sheet not found");
            return List.of();
        }

        List<VendorRationalizationDtos.ParetoRow> rows = new ArrayList<>();
        boolean firstRow = true;
        for (Row row : sheet) {
            if (firstRow) { firstRow = false; continue; }
            if (isRowEmpty(row)) continue;

            try {
                int rank              = (int) cellDouble(row, 0);
                String vendorName     = cellStr(row, 1);
                double spendAmount    = cellDouble(row, 2);
                double cumulativeSpend = cellDouble(row, 3);
                double cumulativePct  = cellDouble(row, 4);

                if (vendorName == null || vendorName.isBlank()) continue;

                rows.add(new VendorRationalizationDtos.ParetoRow(
                        rank, vendorName, spendAmount, cumulativeSpend, cumulativePct, null));
            } catch (Exception e) {
                log.debug("[VendorSpendDataService] Pareto row {}: {}", row.getRowNum(), e.getMessage());
            }
        }
        return Collections.unmodifiableList(rows);
    }

    // ── Cell helpers ──────────────────────────────────────────────────────────

    private String cellStr(Row row, int col) {
        Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                yield (d == Math.floor(d)) ? String.valueOf((long) d) : String.valueOf(d);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try { yield cell.getStringCellValue().trim(); }
                catch (Exception ex) {
                    try { yield String.valueOf(cell.getNumericCellValue()); }
                    catch (Exception ex2) { yield ""; }
                }
            }
            default -> "";
        };
    }

    private double cellDouble(Row row, int col) {
        Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return 0.0;
        return switch (cell.getCellType()) {
            case NUMERIC -> cell.getNumericCellValue();
            case STRING  -> {
                try { yield Double.parseDouble(cell.getStringCellValue().replaceAll("[^0-9.\\-]", "")); }
                catch (NumberFormatException e) { yield 0.0; }
            }
            case FORMULA -> {
                try { yield cell.getNumericCellValue(); }
                catch (Exception e) { yield 0.0; }
            }
            default -> 0.0;
        };
    }

    private boolean isRowEmpty(Row row) {
        if (row == null) return true;
        for (int i = row.getFirstCellNum(); i < row.getLastCellNum(); i++) {
            Cell cell = row.getCell(i);
            if (cell != null && cell.getCellType() != CellType.BLANK) return false;
        }
        return true;
    }

    // ── Snapshot record ───────────────────────────────────────────────────────

    record DataSnapshot(
            List<VendorRationalizationDtos.VendorRow> enrichedData,
            List<VendorRationalizationDtos.ParetoRow> paretoData
    ) {
        static final DataSnapshot EMPTY = new DataSnapshot(List.of(), List.of());
    }
}
