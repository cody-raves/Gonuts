package dev.doughbay.fabric;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Keeps current evidence even when historical log lines are absent or crowded. */
public final class BugReportEvidence {
    private BugReportEvidence() { }
    public static String readForReport(Path path) {
        return readForReport(path,"","");
    }
    public static String readForReport(Path path,String description) {
        return readForReport(path,description,"");
    }
    public static String readForReport(Path path,String description,String expected) {
        String historyToken=dev.doughbay.fabric.hosted.HostedDashboardClient.INSTANCE.diagnosticReportToken();
        ReportTopic topic=ReportTopic.infer(description,expected);
        String context;
        try {context=DoughBayClient.automationSessionController().bugReportContext(System.currentTimeMillis());}
        catch(RuntimeException e){context="Current controller evidence: unavailable\n";}
        String log=BugReportLog.readForReport(path,topic);
        context+=FaultTrace.INSTANCE.report(System.currentTimeMillis());
        context+=ActionTrace.INSTANCE.report(System.currentTimeMillis(),topic);
        context+="ctx historical_counters_available="+!log.equals(BugReportLog.UNAVAILABLE)+"\n";
        String evidence=assemble(context,log,DecisionTrace.INSTANCE,topic,System.currentTimeMillis());
        if(historyToken.equals(dev.doughbay.fabric.hosted.HostedDashboardClient.INSTANCE.diagnosticReportToken()))
            SwarmReportLinks.remember(evidence,historyToken,System.currentTimeMillis());
        return evidence;
    }
    static String combine(String context,String log) {
        return combine(context,log,ReportTopic.GENERAL);
    }
    static String assemble(String context,String log,DecisionTrace trace,ReportTopic topic,long now){
        return combine(context,trace.report(now,topic,11000)+log,topic);
    }
    private static String combine(String context,String log,ReportTopic topic) {
        if(context.getBytes(StandardCharsets.UTF_8).length>7168)throw new IllegalArgumentException("Controller context exceeds report budget");
        var lines=new java.util.ArrayList<>(java.util.List.of(log.split("\n")));
        String headers="";
        if(log.startsWith("Filtered GoNuts diagnostic excerpt;")){
            headers=lines.removeFirst()+"\n";
            if(!lines.isEmpty())headers+=lines.removeFirst()+"\n";
        }
        int historicalStart=0;
        for(int i=0;i<lines.size();i++)if(lines.get(i).equals("Filtered GoNuts diagnostic excerpt; messages, identities and locations omitted.")){historicalStart=i+2;break;}
        int omitted=0;
        while(!lines.isEmpty() && ((context+"\n"+headers+String.join("\n",lines)).getBytes(StandardCharsets.UTF_8).length>BugReportLog.MAX_BYTES-40
                ||lines.size()+context.lines().count()+headers.lines().count()>199)){
            lines.remove(topic==ReportTopic.GENERAL?Math.min(historicalStart,lines.size()-1):lines.size()-1);omitted++;
        }
        for(int i=0;i<lines.size();i++)if(DecisionTrace.validCoverage(lines.get(i))){lines.set(i,lines.get(i)+" history_omitted="+omitted);break;}
        return context+"\n"+headers+String.join("\n",lines);
    }
}
