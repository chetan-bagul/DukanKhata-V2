package com.dukankhata.report;

import com.dukankhata.report.dto.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class ReportPdfService {
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter SHORT_DF = DateTimeFormatter.ofPattern("dd/MM");
    private static final float MARGIN=30;
    private static final float[] XS={MARGIN,78,140,202,258,314,378,442,504};
    private static final String[] HEADS={"Date","Expected","Cash","UPI","Udhar","Actual","Variance","Expense","Net"};

    public byte[] generate(String shopName, ReportResponse report) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            List<DailyReportRow> rows = report.days();
            int index=0; boolean first=true;
            do {
                PDPage page=new PDPage(PDRectangle.A4); doc.addPage(page);
                try(PDPageContentStream cs=new PDPageContentStream(doc,page)) {
                    float y=PDRectangle.A4.getHeight()-MARGIN;
                    y=header(cs,shopName,report,y);
                    if(first){ y=summary(cs,report.summary(),y); y-=10; first=false; }
                    for(int i=0;i<HEADS.length;i++) y=text(cs,HEADS[i],XS[i],y,6.5f,true);
                    y-=3;
                    while(index<rows.size() && y>45){
                        DailyReportRow r=rows.get(index++);
                        String[] vals={r.date().format(SHORT_DF),money(r.expectedSales()),money(r.cash()),money(r.upi()),money(r.udhar()),money(r.actualCollection()),money(r.variance()),money(r.expenses()),money(r.netProfit())};
                        float rowY=y;
                        for(int i=0;i<vals.length;i++) text(cs,vals[i],XS[i],rowY,6.5f,false);
                        y-=16;
                    }
                }
            } while(index<rows.size() || report.days().isEmpty() && doc.getNumberOfPages()==0);
            if(rows.isEmpty() && doc.getNumberOfPages()==0){doc.addPage(new PDPage(PDRectangle.A4));}
            doc.save(out); return out.toByteArray();
        } catch(IOException e){ throw new IllegalStateException("Unable to generate PDF report",e); }
    }

    private float header(PDPageContentStream cs,String shopName,ReportResponse report,float y)throws IOException{
        y=text(cs,"DukanKhata",MARGIN,y,17,true);
        y=text(cs,shopName+" - Sales & Profit Report",MARGIN,y-4,11,false);
        return text(cs,report.summary().from().format(DF)+" to "+report.summary().to().format(DF),MARGIN,y-2,8,false)-10;
    }
    private float summary(PDPageContentStream cs,ReportSummaryResponse s,float y)throws IOException{
        String[] lines={"Closed days: "+s.closedDays(),"Expected: "+money(s.expectedSales()),"Cash: "+money(s.cash()),"UPI: "+money(s.upi()),"Udhar: "+money(s.udhar()),"Repay cash: "+money(s.customerRepaymentCash()),"Repay UPI: "+money(s.customerRepaymentUpi()),"Actual: "+money(s.actualCollection()),"Variance: "+money(s.variance()),"POS sales: "+money(s.posSalesTotal()),"POS cash: "+money(s.posCash()),"POS UPI: "+money(s.posUpi()),"POS Udhar: "+money(s.posUdhar()),"Expenses: "+money(s.expenses()),"Net Profit: "+money(s.netProfit())};
        float x=MARGIN; float rowY=y;
        for(int i=0;i<lines.length;i++){text(cs,lines[i],x,rowY,7,i==14); x+=108; if((i+1)%5==0){x=MARGIN;rowY-=14;}}
        return rowY-18;
    }
    private float text(PDPageContentStream cs,String v,float x,float y,float size,boolean bold)throws IOException{
        cs.beginText(); cs.setFont(bold?new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD):new PDType1Font(Standard14Fonts.FontName.HELVETICA),size); cs.newLineAtOffset(x,y); cs.showText(v==null?"":v); cs.endText(); return y-size-2;
    }
    private String money(BigDecimal v){return "INR "+v.setScale(2).toPlainString();}
}
