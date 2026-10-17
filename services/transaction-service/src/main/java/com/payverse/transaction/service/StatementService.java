package com.payverse.transaction.service;

import com.payverse.common.money.Money;
import com.payverse.transaction.entity.Transaction;
import com.payverse.transaction.entity.TransactionStatus;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/** Account statements as CSV or PDF (OpenPDF). Counterparties appear only in masked form. */
@Service
public class StatementService {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm").withZone(INDIA);

    record Row(Instant at, String description, String direction, Money amount, String status, UUID id) {
    }

    public byte[] csv(UUID viewer, List<Transaction> transactions) {
        StringBuilder sb = new StringBuilder("date,description,type,amount,currency,status,transaction_id\n");
        for (Row r : rows(viewer, transactions)) {
            sb.append(TIME.format(r.at())).append(',')
                    .append(escape(r.description())).append(',')
                    .append(r.direction()).append(',')
                    .append(r.amount().toPlainString()).append(",INR,")
                    .append(r.status()).append(',')
                    .append(r.id()).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public byte[] pdf(UUID viewer, List<Transaction> transactions, Instant from, Instant to) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 36, 36, 36, 36);
        PdfWriter.getInstance(document, out);
        document.open();
        Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
        Font small = FontFactory.getFont(FontFactory.HELVETICA, 9);
        Font header = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
        document.add(new Paragraph("PayVerse wallet statement", title));
        document.add(new Paragraph("Period: " + TIME.format(from) + " – " + TIME.format(to) + " (IST)", small));
        document.add(new Paragraph("Generated: " + TIME.format(Instant.now()), small));
        document.add(new Paragraph(" "));

        PdfPTable table = new PdfPTable(new float[]{2.2f, 4.5f, 1.2f, 1.8f, 1.6f});
        table.setWidthPercentage(100);
        for (String h : List.of("Date", "Description", "Type", "Amount (INR)", "Status")) {
            table.addCell(new PdfPCell(new Phrase(h, header)));
        }
        Money credits = Money.zeroInr();
        Money debits = Money.zeroInr();
        for (Row r : rows(viewer, transactions)) {
            table.addCell(new Phrase(TIME.format(r.at()), small));
            table.addCell(new Phrase(r.description(), small));
            table.addCell(new Phrase(r.direction(), small));
            PdfPCell amount = new PdfPCell(new Phrase(r.amount().toPlainString(), small));
            amount.setHorizontalAlignment(Element.ALIGN_RIGHT);
            table.addCell(amount);
            table.addCell(new Phrase(r.status(), small));
            if ("SUCCESS".equals(r.status())) {
                if ("CREDIT".equals(r.direction())) {
                    credits = credits.plus(r.amount());
                } else {
                    debits = debits.plus(r.amount());
                }
            }
        }
        document.add(table);
        document.add(new Paragraph(" "));
        document.add(new Paragraph("Total received: INR " + credits.toPlainString() + "    Total sent: INR " + debits.toPlainString(), header));
        document.close();
        return out.toByteArray();
    }

    private static List<Row> rows(UUID viewer, List<Transaction> transactions) {
        return transactions.stream().map(t -> {
            boolean sent = t.getPayerUserId().equals(viewer);
            String counterparty = sent ? "To " + t.getPayeeRef() : "From " + (t.getPayerRef() == null ? "PayVerse user" : t.getPayerRef());
            String description = t.getNote() == null ? counterparty : counterparty + " — " + t.getNote();
            String status = t.getStatus() == TransactionStatus.REVERSED ? "REVERSED" : "SUCCESS";
            return new Row(t.getCompletedAt() == null ? t.getCreatedAt() : t.getCompletedAt(), description,
                    sent ? "DEBIT" : "CREDIT", t.getAmountMoney(), status, t.getId());
        }).toList();
    }

    /** RFC 4180 quoting; also neutralises spreadsheet formula injection. */
    static String escape(String value) {
        String safe = value.matches("^[=+\\-@].*") ? "'" + value : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
