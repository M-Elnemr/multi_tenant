package com.platform.medical;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.FontSelector;
import com.platform.shared.BusinessException;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Printable prescription: clinic and doctor branding, patient, medications, notes. Arabic is shaped and laid out right-to-left with an embedded
 * Noto Naskh Arabic font; Latin text uses Helvetica (picked per character, so mixed names and drug names render correctly).
 */
@Service
public class PrescriptionPdfService {
    private static final Map<String, String[]> LABELS = Map.ofEntries(
            Map.entry("title", new String[] {"وصفة طبية", "Prescription"}),
            Map.entry("doctor", new String[] {"الطبيب", "Doctor"}),
            Map.entry("specialty", new String[] {"التخصص", "Specialty"}),
            Map.entry("patient", new String[] {"المريض", "Patient"}),
            Map.entry("code", new String[] {"كود المريض", "Patient code"}),
            Map.entry("age", new String[] {"العمر", "Age"}),
            Map.entry("date", new String[] {"التاريخ", "Date"}),
            Map.entry("medication", new String[] {"الدواء", "Medication"}),
            Map.entry("strength", new String[] {"التركيز", "Strength"}),
            Map.entry("dose", new String[] {"الجرعة", "Dose"}),
            Map.entry("frequency", new String[] {"عدد المرات", "Frequency"}),
            Map.entry("duration", new String[] {"المدة", "Duration"}),
            Map.entry("instructions", new String[] {"التعليمات", "Instructions"}),
            Map.entry("notes", new String[] {"ملاحظات", "Notes"}),
            Map.entry("footer", new String[] {"صدرت إلكترونيًا. رقم الوصفة", "Issued electronically. Prescription no."}),
            Map.entry("cancelled", new String[] {"ملغاة", "CANCELLED"}),
            Map.entry("draft", new String[] {"مسودة", "DRAFT"}));

    private final JdbcClient jdbc;
    private final BaseFont arabic;
    private final BaseFont latin;
    private final BaseFont latinBold;
    private final com.platform.files.FileService files;

    public PrescriptionPdfService(JdbcClient jdbc, com.platform.files.FileService files) throws Exception {
        this.jdbc = jdbc;
        this.files = files;
        byte[] ttf;
        try (InputStream in = getClass().getResourceAsStream("/fonts/NotoNaskhArabic.ttf")) {
            ttf = in.readAllBytes();
        }
        this.arabic = BaseFont.createFont("NotoNaskhArabic.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, ttf, null);
        this.latin = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
        this.latinBold = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
    }

    private Phrase text(String s, float size, boolean bold) {
        FontSelector sel = new FontSelector();
        sel.addFont(new Font(bold ? latinBold : latin, size));
        sel.addFont(new Font(arabic, size));
        return sel.process(s == null ? "" : s);
    }

    /** patientView=true only ever renders ISSUED prescriptions. */
    public byte[] render(UUID tenantId, UUID prescriptionId, boolean patientView) {
        var p = jdbc.sql("""
                SELECT rx.id, rx.status, rx.issued_at, rx.created_at, rx.notes, rx.image_file_id, d.display_name AS doctor_name, d.id AS doctor_id,
                       pt.first_name || ' ' || pt.last_name AS patient_name, pt.patient_code, pt.date_of_birth,
                       cp.clinic_name, cp.phone AS clinic_phone, cp.address_text AS clinic_address, t.default_locale, t.timezone
                FROM medical.prescriptions rx JOIN medical.doctors d ON d.id = rx.prescribed_by JOIN medical.patients pt ON pt.id = rx.patient_id
                JOIN core.tenants t ON t.id = rx.tenant_id LEFT JOIN medical.clinic_profiles cp ON cp.tenant_id = rx.tenant_id
                WHERE rx.id = :i AND rx.tenant_id = :t
                """).param("i", prescriptionId).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Prescription not found"));
        String status = (String) p.get("status");
        if (patientView && !"ISSUED".equals(status)) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Prescription not found");
        var items = jdbc.sql("SELECT medication_name, strength, dosage, frequency, duration, instructions FROM medical.prescription_items WHERE prescription_id = :p ORDER BY sort_order").param("p", prescriptionId).query().listOfRows();
        String specialties = String.join(", ", jdbc.sql("""
                SELECT CASE WHEN s.code = 'other' THEN coalesce(d.other_specialty, s.name_en) ELSE s.name_en || ' / ' || s.name_ar END
                FROM medical.doctor_specialties ds JOIN medical.specialties s ON s.id = ds.specialty_id JOIN medical.doctors d ON d.id = ds.doctor_id
                WHERE ds.doctor_id = :d ORDER BY s.sort_order
                """).param("d", p.get("doctor_id")).query(String.class).list());

        boolean rtl = String.valueOf(p.get("default_locale")).startsWith("ar");
        int li = rtl ? 0 : 1;
        int dir = rtl ? PdfWriter.RUN_DIRECTION_RTL : PdfWriter.RUN_DIRECTION_LTR;
        ZoneId tz = ZoneId.of((String) p.get("timezone"));
        java.sql.Timestamp when = (java.sql.Timestamp) (p.get("issued_at") != null ? p.get("issued_at") : p.get("created_at"));
        LocalDate day = when.toInstant().atZone(tz).toLocalDate();
        String ageText = "";
        if (p.get("date_of_birth") != null) ageText = String.valueOf(java.time.Period.between(((java.sql.Date) p.get("date_of_birth")).toLocalDate(), day).getYears());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 36, 36, 40, 50);
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setRunDirection(dir);
            final String watermark = "CANCELLED".equals(status) ? LABELS.get("cancelled")[li] : "DRAFT".equals(status) ? LABELS.get("draft")[li] : null;
            final String footer = LABELS.get("footer")[li] + " " + prescriptionId.toString().substring(0, 8).toUpperCase();
            writer.setPageEvent(new PdfPageEventHelper() {
                @Override public void onEndPage(PdfWriter w, Document d) {
                    PdfContentByte cb = w.getDirectContent();
                    if (watermark != null) {
                        cb.saveState();
                        cb.setColorFill(new Color(220, 38, 38, 255));
                        PdfContentByte under = w.getDirectContentUnder();
                        under.saveState();
                        under.setGState(new com.lowagie.text.pdf.PdfGState() {{ setFillOpacity(0.15f); }});
                        under.setColorFill(new Color(220, 38, 38));
                        ColumnText.showTextAligned(under, Element.ALIGN_CENTER, text(watermark, 90, true), PageSize.A4.getWidth() / 2, PageSize.A4.getHeight() / 2, 40, dir, 0);
                        under.restoreState();
                        cb.restoreState();
                    }
                    ColumnText.showTextAligned(cb, Element.ALIGN_CENTER, text(footer, 8, false), PageSize.A4.getWidth() / 2, 28, 0, dir, 0);
                }
            });
            doc.open();

            Paragraph head = new Paragraph(text((String) p.get("clinic_name"), 20, true));
            head.setAlignment(rtl ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
            doc.add(head);
            StringBuilder contact = new StringBuilder();
            if (p.get("clinic_address") != null) contact.append(p.get("clinic_address"));
            if (p.get("clinic_phone") != null) contact.append(contact.length() > 0 ? "  |  " : "").append(p.get("clinic_phone"));
            if (contact.length() > 0) {
                Paragraph c = new Paragraph(text(contact.toString(), 9, false));
                c.setAlignment(rtl ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
                doc.add(c);
            }
            Paragraph title = new Paragraph(text(LABELS.get("title")[li], 16, true));
            title.setSpacingBefore(14);
            title.setSpacingAfter(8);
            title.setAlignment(Element.ALIGN_CENTER);
            doc.add(title);

            PdfPTable meta = new PdfPTable(4);
            meta.setWidthPercentage(100);
            meta.setRunDirection(dir);
            meta.setWidths(new float[] {1.1f, 2f, 1.1f, 2f});
            metaRow(meta, dir, LABELS.get("doctor")[li], (String) p.get("doctor_name"), LABELS.get("date")[li], DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH).format(day));
            metaRow(meta, dir, LABELS.get("patient")[li], (String) p.get("patient_name"), LABELS.get("code")[li], (String) p.get("patient_code"));
            metaRow(meta, dir, LABELS.get("specialty")[li], specialties, LABELS.get("age")[li], ageText);
            doc.add(meta);

            if (!items.isEmpty()) {
            PdfPTable t = new PdfPTable(7);
            t.setWidthPercentage(100);
            t.setRunDirection(dir);
            float[] cols = {0.4f, 2.4f, 1f, 1f, 1.2f, 1.1f, 2f};
            if (dir == PdfWriter.RUN_DIRECTION_RTL) for (int a = 0, b = cols.length - 1; a < b; a++, b--) { float x = cols[a]; cols[a] = cols[b]; cols[b] = x; }   // widths are laid out left to right
            t.setWidths(cols);
            t.setSpacingBefore(16);
            t.setHeaderRows(1);
            for (String h : new String[] {"#", "medication", "strength", "dose", "frequency", "duration", "instructions"}) {
                PdfPCell c = cell("#".equals(h) ? "#" : LABELS.get(h)[li], dir, 9, true);
                c.setBackgroundColor(new Color(15, 118, 110));
                c.setPhrase(whiteText("#".equals(h) ? "#" : LABELS.get(h)[li]));
                t.addCell(c);
            }
            int n = 1;
            for (var it : items) {
                t.addCell(cell(String.valueOf(n++), dir, 10, false));
                t.addCell(cell(str(it.get("medication_name")), dir, 10, true));
                t.addCell(cell(str(it.get("strength")), dir, 10, false));
                t.addCell(cell(str(it.get("dosage")), dir, 10, false));
                t.addCell(cell(str(it.get("frequency")), dir, 10, false));
                t.addCell(cell(str(it.get("duration")), dir, 10, false));
                t.addCell(cell(str(it.get("instructions")), dir, 9, false));
            }
            doc.add(t);
            }
            if (p.get("image_file_id") != null) {
                // the doctor's own paper prescription (photo/scan), scaled to fit the page
                var raw = files.readForExport(tenantId, (UUID) p.get("image_file_id"));
                if (raw.isPresent()) {
                    com.lowagie.text.Image img = com.lowagie.text.Image.getInstance(raw.get().data());
                    img.scaleToFit(doc.getPageSize().getWidth() - doc.leftMargin() - doc.rightMargin(), 560);
                    img.setAlignment(Element.ALIGN_CENTER);
                    img.setSpacingBefore(14);
                    doc.add(img);
                }
            }
            if (p.get("notes") != null && !String.valueOf(p.get("notes")).isBlank()) {
                Paragraph nl = new Paragraph(text(LABELS.get("notes")[li], 11, true));
                nl.setSpacingBefore(16);
                nl.setAlignment(rtl ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
                doc.add(nl);
                PdfPTable nt = new PdfPTable(1);
                nt.setWidthPercentage(100);
                nt.setRunDirection(dir);
                nt.addCell(cell(str(p.get("notes")), dir, 10, false));
                doc.add(nt);
            }
            doc.close();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate the prescription PDF", e);
        }
        return out.toByteArray();
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }

    private Phrase whiteText(String s) {
        FontSelector sel = new FontSelector();
        sel.addFont(new Font(latinBold, 9, Font.NORMAL, Color.WHITE));
        sel.addFont(new Font(arabic, 9, Font.NORMAL, Color.WHITE));
        return sel.process(s);
    }

    private PdfPCell cell(String s, int dir, float size, boolean bold) {
        PdfPCell c = new PdfPCell(text(s, size, bold));
        c.setRunDirection(dir);
        c.setPadding(6);
        c.setBorderColor(new Color(203, 213, 225));
        c.setHorizontalAlignment(dir == PdfWriter.RUN_DIRECTION_RTL ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
        return c;
    }

    private void metaRow(PdfPTable t, int dir, String l1, String v1, String l2, String v2) {
        for (String[] kv : new String[][] {{l1, "k"}, {v1, "v"}, {l2, "k"}, {v2, "v"}}) {
            PdfPCell c = cell(kv[0] == null ? "" : kv[0], dir, 10, "k".equals(kv[1]));
            c.setBorder(Rectangle.BOTTOM);
            if ("k".equals(kv[1])) c.setBackgroundColor(new Color(241, 245, 249));
            t.addCell(c);
        }
    }

}
