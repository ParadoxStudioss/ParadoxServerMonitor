package dev.paradox.monitor;

import net.neoforged.fml.common.Mod;
import net.neoforged.api.distmarker.Dist;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

@Mod(value="paradoxmonitor", dist=Dist.DEDICATED_SERVER)
public final class ServerMonitor {
    private static final Path CONFIG=Path.of("config/paradoxmonitor.properties");
    private static final Pattern HEADER=Pattern.compile("^\\[([^]]+)] \\[([^]]+)/(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)](?: \\[([^]]+)])?: (.*)$");
    private static final Pattern JOIN=Pattern.compile("^([A-Za-z0-9_]{1,16}) joined the game$");
    private static final Pattern LEFT=Pattern.compile("^([A-Za-z0-9_]{1,16}) left the game$");
    private static final Pattern LOST=Pattern.compile("^([A-Za-z0-9_]{1,16}) lost connection: (.*)$");
    private static final Pattern PROFILE=Pattern.compile("^com\\.mojang\\.authlib\\.GameProfile.*?\\bname=([A-Za-z0-9_]{1,16})(?:,|\\]).*? lost connection: (.*)$");
    private static final Pattern IPV6=Pattern.compile("(?i)(?<![0-9a-z])(?:\\[[0-9a-f:.%a-z_-]*:[0-9a-f:.%a-z_-]+](?::\\d{1,5})?|(?:[0-9a-f]{0,4}:){2,}[0-9a-f:.%_-]*(?:%[a-z0-9_-]+)?)(?![0-9a-z])");
    private static final Pattern IPV4=Pattern.compile("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?::[0-9]{1,5})?(?![0-9])");
    private static final Pattern SECRET=Pattern.compile("(?i)https?://(?:[a-z]+\\.)?discord(?:app)?\\.com/api/(?:v\\d+/)?webhooks/[^\\s\\]\\)<>\"']+");
    private static final Set<String> NORMAL=Set.of("disconnected","disconnect.quitting","disconnect.disconnected","multiplayer.disconnect.quitting","server closed","server shutting down","multiplayer.disconnect.server_shutdown");
    private static final Pattern ABNORMAL=Pattern.compile("(?i)crash|exception|timed? out|timeout|connection reset|broken pipe|end of stream|network protocol error|connection aborted|packet.*(?:error|fail)|failed to (?:decode|encode|synchronize)|internal error|unexpected error|disconnect\\.genericreason");
    private static final Pattern EXPLICIT=Pattern.compile("(?i)\\bcrash(?:ed|ing)?\\b");
    private final Map<String,Long> recentlyLeft=new HashMap<>(), recentlyCrashed=new HashMap<>();
    private final BlockingQueue<Notice> queue=new ArrayBlockingQueue<>(512);
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Properties config=new Properties();
    private record Notice(String key,String title,String body,String time,int color) {}

    public ServerMonitor() {
        try {
            Files.createDirectories(CONFIG.getParent());
            if(Files.notExists(CONFIG)) Files.writeString(CONFIG,"# Paste Discord webhook URLs below. Empty = disabled. Restart after editing.\njoinWebhook=\nleaveWebhook=\ncrashWebhook=\nerrorWebhook=\nserverName=Minecraft Server\nlogPath=logs/latest.log\n",StandardOpenOption.CREATE_NEW);
            try(Reader r=Files.newBufferedReader(CONFIG)){config.load(r);}
            start("paradoxmonitor-sender",this::sendLoop);
            start("paradoxmonitor-log-reader",this::tail);
            System.out.println("[ParadoxMonitor] Console monitor started.");
        } catch(Exception e){System.err.println("[ParadoxMonitor] Could not initialize (check config file permissions/format).");}
    }
    private static void start(String name,Runnable work){Thread t=new Thread(work,name);t.setDaemon(true);t.start();}
    private void tail(){
        Path log=Path.of(config.getProperty("logPath","logs/latest.log"));
        long pos=0; Object key=null; boolean opened=false;
        while(!Thread.currentThread().isInterrupted()){
            try{
                if(Files.isRegularFile(log)){
                    BasicFileAttributes a=Files.readAttributes(log,BasicFileAttributes.class);
                    if(!opened || !Objects.equals(key,a.fileKey()) || a.size()<pos){pos=0;key=a.fileKey();opened=true;}
                    try(RandomAccessFile f=new RandomAccessFile(log.toFile(),"r")){
                        f.seek(pos); String line;
                        while((line=f.readLine())!=null){
                            long end=f.getFilePointer();
                            if(end==f.length()){f.seek(end-1);int last=f.read();f.seek(end);if(last!='\n'&&last!='\r')break;}
                            pos=end;
                            accept(new String(line.getBytes(StandardCharsets.ISO_8859_1),StandardCharsets.UTF_8));
                        }
                    }
                }
                Thread.sleep(400);
            } catch(InterruptedException e){Thread.currentThread().interrupt();return;} catch(Exception e){try{Thread.sleep(2000);}catch(InterruptedException stop){return;}}
        }
    }
    void accept(String line){
        if(line.contains("[ParadoxMonitor]"))return;
        Matcher h=HEADER.matcher(line); if(!h.matches())return;
        String time=h.group(1),level=h.group(3),logger=Objects.toString(h.group(4),""),msg=h.group(5);
        if(level.equals("ERROR")||level.equals("FATAL"))emit("errorWebhook","Server "+level,redact((logger.isEmpty()?"":"["+logger+"] ")+msg),time,0xED4245);
        if(!logger.isEmpty() && !logger.matches(".*(?:MinecraftServer|PlayerList|Server(?:Game|Configuration|Login|Common)PacketListenerImpl)(?:/.*)?"))return;
        Matcher j=JOIN.matcher(msg),l=LEFT.matcher(msg),d=LOST.matcher(msg),p=PROFILE.matcher(msg);
        if(j.matches()){String name=j.group(1);recentlyLeft.remove(name);recentlyCrashed.remove(name);emit("joinWebhook","Player joined",name,time,0x57F287);return;}
        if(d.matches()||p.matches()){
            Matcher m=d.matches()?d:p;String name=m.group(1),reason=m.group(2).trim();
            boolean explicit=EXPLICIT.matcher(reason).find();
            boolean abnormal=!NORMAL.contains(reason.toLowerCase(Locale.ROOT))&&ABNORMAL.matcher(reason).find();
            if(abnormal||explicit){if(!duplicate(recentlyLeft,name))emit("leaveWebhook",explicit?"Player crashed":"Player disconnected (suspected crash)",name,time,0xFEE75C);if(!duplicate(recentlyCrashed,name))emit("crashWebhook",explicit?"Player crashed":"Suspected player crash",name+"\nConsole: "+redact(name+" lost connection: "+reason),time,0xED4245); }
            return;
        }
        if(l.matches()&&!duplicate(recentlyLeft,l.group(1)))emit("leaveWebhook","Player left",l.group(1),time,0xFEE75C);
    }
    private boolean duplicate(Map<String,Long> cache,String name){long now=System.nanoTime();cache.entrySet().removeIf(e->now-e.getValue()>TimeUnit.SECONDS.toNanos(10));return cache.put(name,now)!=null;}
    static String redact(String s){return IPV4.matcher(IPV6.matcher(SECRET.matcher(s).replaceAll("[WEBHOOK REDACTED]")).replaceAll("[IP REDACTED]")).replaceAll("[IP REDACTED]");}
    private void emit(String key,String title,String body,String time,int color){
        if(config.getProperty(key,"").isBlank())return;
        if(!queue.offer(new Notice(key,title,body,time,color)))System.err.println("[ParadoxMonitor] Notification queue full; dropped event.");
    }
    private void sendLoop(){while(!Thread.currentThread().isInterrupted())try{send(queue.take());}catch(InterruptedException e){Thread.currentThread().interrupt();return;}catch(Exception e){System.err.println("[ParadoxMonitor] Webhook delivery failed.");}}
    private void send(Notice n)throws Exception{
        URI uri=URI.create(config.getProperty(n.key()).trim());String host=uri.getHost();
        if(!"https".equalsIgnoreCase(uri.getScheme())||host==null||!Set.of("discord.com","discordapp.com","canary.discord.com","ptb.discord.com").contains(host.toLowerCase(Locale.ROOT))||!uri.getPath().matches("/api/(?:v\\d+/)?webhooks/\\d+/[A-Za-z0-9_-]+")||uri.getUserInfo()!=null) return;
        String body=redact(n.body());if(body.length()>3500)body=body.substring(0,3500)+"…";
        String json="{\"allowed_mentions\":{\"parse\":[]},\"embeds\":[{\"title\":"+q(n.title())+",\"description\":"+q(body)+",\"color\":"+n.color()+",\"footer\":{\"text\":"+q(redact(config.getProperty("serverName","Minecraft Server"))+" | Console: "+n.time())+"},\"timestamp\":"+q(Instant.now().toString())+"}]}";
        HttpRequest req=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build();
        for(int i=0;i<4;i++){
            HttpResponse<String> response;
            try{response=http.send(req,HttpResponse.BodyHandlers.ofString());}catch(IOException e){if(i==3)throw e;Thread.sleep(1000L*(i+1));continue;}
            int status=response.statusCode();if(status>=200&&status<300)return;
            if(status==429||status>=500){long delay=2000L*(i+1);if(status==429)try{delay=(long)(Double.parseDouble(response.headers().firstValue("Retry-After").orElse("2"))*1000)+250;}catch(NumberFormatException ignored){}Thread.sleep(Math.max(500,Math.min(60000,delay)));continue;}
            System.err.println("[ParadoxMonitor] Discord rejected webhook (HTTP "+status+").");return;
        }
        System.err.println("[ParadoxMonitor] Webhook retry limit reached.");
    }
    static String q(String s){StringBuilder b=new StringBuilder("\"");for(char c:s.toCharArray()){switch(c){case '\\'->b.append("\\\\");case '"'->b.append("\\\"");default->{if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}}}return b.append('"').toString();}
}
