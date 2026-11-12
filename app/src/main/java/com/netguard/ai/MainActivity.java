package com.netguard.ai;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.os.Build;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import android.content.res.ColorStateList;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int NAVY=Color.rgb(9,18,34), PANEL=Color.rgb(20,34,54);
    private static final int GREEN=Color.rgb(45,205,133), WHITE=Color.WHITE;
    private static final int MUTED=Color.rgb(165,181,201), AMBER=Color.rgb(255,190,75);
    private static final String PREFS="netguard_local_history";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ExecutorService scanWorker=Executors.newSingleThreadExecutor();
    private final ArrayList<Sample> history=new ArrayList<>();
    private boolean running=false, gameMode=false, probeInFlight=false, outageAlertSent=false;
    private int intervalMs=10000, consecutiveFailures=0;
    private long lastGoodLatency=-1;
    private double jitterEwma=0;
    private long totalChecks=0, tcpFailures=0;
    private Runnable loop;
    private TextView status, transport, internet, router, jitter, failureRate, diagnostic, updated, summary, devices;
    private Button toggle, modeButton;
    private Spinner interval;
    private Graph graph;

    static final class Sample {
        final long time, internetMs, alternateMs, routerMs, routerHttpsMs;
        final boolean internetOk, primaryOk, alternateOk;
        Sample(long t,long i,long a,long r,long rh,boolean po,boolean ao){
            time=t;internetMs=i;alternateMs=a;routerMs=r;routerHttpsMs=rh;
            primaryOk=po;alternateOk=ao;internetOk=po||ao;
        }
        long effectiveLatency(){return primaryOk?internetMs:(alternateOk?alternateMs:-1);}
    }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(NAVY);
        getWindow().setNavigationBarColor(NAVY);
        loadHistory();
        intervalMs=getSharedPreferences(PREFS,MODE_PRIVATE).getInt("interval_ms",10000);
        gameMode=getSharedPreferences(PREFS,MODE_PRIVATE).getBoolean("game_mode",false);
        buildUi();
        applyMode();
        renderHistory();
        updateTransport();
        ensureNotificationPermission();
    }

    private void ensureNotificationPermission(){
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},71);
    }
    private void postNetworkAlert(String title,String message){
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=android.content.pm.PackageManager.PERMISSION_GRANTED)return;
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(manager==null)return;
        String channelId="netguard_network_events";
        if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(new NotificationChannel(channelId,"Alertas de conexão",NotificationManager.IMPORTANCE_DEFAULT));
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent pending=PendingIntent.getActivity(this,71,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,channelId)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(message)
            .setStyle(new Notification.BigTextStyle().bigText(message)).setContentIntent(pending).setAutoCancel(true).build();
        manager.notify(71,notification);
    }

    private void buildUi() {
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(NAVY);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18),dp(22),dp(18),dp(28)); scroll.addView(root);

        LinearLayout brandRow=horizontal();
        ImageView logo=new ImageView(this);
        logo.setImageResource(R.drawable.netguard_logo);
        brandRow.addView(logo,new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView brand=txt("NETGUARD AI",24,WHITE,true);
        LinearLayout brandText=new LinearLayout(this);brandText.setOrientation(LinearLayout.VERTICAL);
        brandText.setPadding(dp(10),0,0,0);brandText.addView(brand);
        brandText.addView(txt("Diagnóstico e estabilidade da conexão",12,MUTED,false));
        brandRow.addView(brandText);root.addView(brandRow);
        LinearLayout state=card(); status=txt("●  Monitoramento pausado",16,MUTED,true);
        state.addView(status); transport=txt("Rede: identificando…",13,MUTED,false); state.addView(transport);
        root.addView(state);

        LinearLayout row=horizontal(); internet=metric(row,"LATÊNCIA TCP","-- ms");
        router=metric(row,"ROTEADOR TCP","-- ms"); root.addView(row);
        LinearLayout row2=horizontal(); jitter=metric(row2,"VARIAÇÃO (JITTER EST.)","-- ms");
        failureRate=metric(row2,"FALHAS TCP","0%"); root.addView(row2);

        LinearLayout controls=card();
        controls.addView(txt("CONTROLES",11,MUTED,true));
        LinearLayout buttons=horizontal();
        toggle=button("Iniciar monitoramento",GREEN,NAVY); toggle.setOnClickListener(v->toggleMonitoring());
        buttons.addView(toggle,new LinearLayout.LayoutParams(0,dp(48),1));
        modeButton=button("Modo: Geral",Color.rgb(44,64,91),WHITE); modeButton.setOnClickListener(v->toggleMode());
        buttons.addView(modeButton,new LinearLayout.LayoutParams(0,dp(48),1)); controls.addView(buttons);
        controls.addView(txt("Intervalo entre verificações",12,MUTED,false));
        interval=new Spinner(this);
        String[] choices={"5 segundos","10 segundos","30 segundos","60 segundos"};
        interval.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,choices));
        interval.setSelection(intervalMs==5000?0:intervalMs==30000?2:intervalMs==60000?3:1);
        interval.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){intervalMs=new int[]{5000,10000,30000,60000}[pos];getSharedPreferences(PREFS,MODE_PRIVATE).edit().putInt("interval_ms",intervalMs).apply();}
            public void onNothingSelected(android.widget.AdapterView<?> p){}
        });
        controls.addView(interval);
        Button scan=button("Buscar dispositivos na rede",GREEN,NAVY);
        scan.setOnClickListener(v->scanDevices(scan));
        controls.addView(scan);
        Button routerPanel=button("Abrir painel do roteador",Color.rgb(44,64,91),WHITE);
        routerPanel.setOnClickListener(v->{
            try{startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("http://192.168.15.1")));}
            catch(Exception e){Toast.makeText(this,"Não foi possível abrir o painel do roteador.",Toast.LENGTH_LONG).show();}
        });
        controls.addView(routerPanel);
        devices=txt("Dispositivos: ainda não pesquisados.",12,MUTED,false);
        controls.addView(devices);
        root.addView(controls);

        LinearLayout chart=card(); chart.addView(txt("HISTÓRICO DE LATÊNCIA TCP",15,WHITE,true));
        graph=new Graph(this); chart.addView(graph,new LinearLayout.LayoutParams(-1,dp(175)));
        updated=txt("Ainda não há medições.",12,MUTED,false); chart.addView(updated);
        summary=txt("Resumo: aguardando medições.",12,MUTED,false); chart.addView(summary);
        root.addView(chart);

        LinearLayout diagnosis=card(); diagnosis.addView(txt("DIAGNÓSTICO BASEADO EM EVIDÊNCIAS",15,WHITE,true));
        diagnostic=txt("Inicie o monitoramento para reunir dados. Nenhum diagnóstico será presumido.",13,MUTED,false);
        diagnosis.addView(diagnostic); root.addView(diagnosis);

        LinearLayout tools=card(); tools.addView(txt("DADOS E PRIVACIDADE",15,WHITE,true));
        LinearLayout toolrow=horizontal();
        Button export=button("Exportar CSV",GREEN,NAVY); export.setOnClickListener(v->exportCsv());
        toolrow.addView(export,new LinearLayout.LayoutParams(0,dp(46),1));
        Button clear=button("Apagar histórico",Color.rgb(65,77,96),WHITE);
        clear.setOnClickListener(v->confirmClear()); toolrow.addView(clear,new LinearLayout.LayoutParams(0,dp(46),1));
        tools.addView(toolrow);
        tools.addView(txt("Os dados ficam neste aparelho. O app não altera o roteador nem envia medições para servidores.",11,MUTED,false));
        root.addView(tools);

        root.addView(txt("A latência é tempo de conexão TCP (Cloudflare 1.1.1.1 e Google 8.8.8.8), não ping ICMP. Falhas TCP não são perda de pacotes IP. O roteador é testado nas portas 80 e 443; portas fechadas não significam que esteja offline.",11,MUTED,false));
        root.addView(txt("O monitoramento desta versão funciona enquanto o aplicativo permanece aberto. Não oferece monitoramento 24/7.",11,AMBER,true));
        setContentView(scroll);
    }

    private LinearLayout horizontal(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private LinearLayout card(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(14),dp(12),dp(14),dp(12));c.setBackgroundColor(PANEL);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(12),0,0);c.setLayoutParams(p);return c;}
    private TextView txt(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(3),0,dp(5));if(bold)t.setTypeface(null,android.graphics.Typeface.BOLD);return t;}
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private Button button(String s,int bg,int fg){Button b=new Button(this);b.setText(s);b.setTextSize(12);b.setTextColor(fg);b.setBackgroundTintList(ColorStateList.valueOf(bg));return b;}
    private TextView metric(LinearLayout parent,String label,String value){LinearLayout c=card();c.addView(txt(label,10,MUTED,true));TextView v=txt(value,21,WHITE,true);c.addView(v);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(84),1);p.setMargins(dp(3),0,dp(3),0);parent.addView(c,p);return v;}

    private void toggleMonitoring(){
        running=!running;
        if(running){toggle.setText("Pausar monitoramento");status.setText("●  Monitoramento ativo");status.setTextColor(GREEN);
            loop=new Runnable(){public void run(){if(!running)return;runProbe();handler.postDelayed(this,intervalMs);}};loop.run();
        }else{if(loop!=null)handler.removeCallbacks(loop);toggle.setText("Iniciar monitoramento");status.setText("●  Monitoramento pausado");status.setTextColor(MUTED);}
    }
    private void scanDevices(Button button){
        button.setEnabled(false);
        devices.setText("Pesquisando dispositivos acessíveis na rede local…");
        scanWorker.execute(()->LanScanner.scan(this,(subnet,found,error)->runOnUiThread(()->{
            button.setEnabled(true);
            if(error!=null){devices.setText("Busca indisponível: "+error);return;}
            StringBuilder out=new StringBuilder("Sub-rede: "+subnet+"\nHosts com resposta: "+found.size()+"\nNota: a busca identifica apenas hosts que responderam ao ARP ou a portas TCP testadas; não é a lista completa do roteador.");
            for(LanScanner.Device d:found){
                out.append("\n").append(d.ip).append(" · ").append(d.mac)
                   .append(" · portas: ").append(d.services);
            }
            devices.setText(out.toString());
        })));
    }
    private void toggleMode(){gameMode=!gameMode;getSharedPreferences(PREFS,MODE_PRIVATE).edit().putBoolean("game_mode",gameMode).apply();applyMode();}
    private void applyMode(){
        if(modeButton!=null)modeButton.setText(gameMode?"Modo: Jogos":"Modo: Geral");
        if(diagnostic!=null)diagnostic.setText(gameMode
            ?"Perfil Rocket League — recomendações seguras:\n1. Mantenha o console/PC ligado diretamente por Ethernet.\n2. Evite downloads, streaming pesado e backups durante as partidas.\n3. Se o roteador oferecer QoS oficialmente, priorize o dispositivo de jogo sem limitar a banda à toa.\n4. Compare medições em horários diferentes. O celular não mede o ping real do jogo nem altera o tráfego do console."
            :"Perfil geral: comparação de dois destinos externos e verificação TCP auxiliar do roteador. As medições não são ping ICMP nem perda de pacotes IP.");
    }

    private void runProbe(){
        if(probeInFlight)return; probeInFlight=true; updateTransport();
        worker.execute(()->{
            long net=probe("1.1.1.1",443,1800);
            long alt=probe("8.8.8.8",443,1800);
            long gw=probe("192.168.15.1",80,700);
            long gwHttps=probe("192.168.15.1",443,700);
            long now=System.currentTimeMillis(); boolean primaryOk=net>=0, alternateOk=alt>=0;
            long effective=primaryOk?net:(alternateOk?alt:-1);
            synchronized(history){
                totalChecks++; if(effective<0){tcpFailures++;consecutiveFailures++;}else consecutiveFailures=0;
                if(effective>=0&&lastGoodLatency>=0){double delta=Math.abs(effective-lastGoodLatency);jitterEwma=(jitterEwma==0?delta:(0.25*delta+0.75*jitterEwma));}
                if(effective>=0)lastGoodLatency=effective;
                history.add(new Sample(now,net,alt,gw,gwHttps,primaryOk,alternateOk));while(history.size()>2000)history.remove(0);
                saveHistoryLocked();
            }
            runOnUiThread(()->{
                probeInFlight=false;renderHistory();diagnoseLatest();
                if(consecutiveFailures>=2&&!outageAlertSent){outageAlertSent=true;postNetworkAlert("Possível interrupção da internet","Dois ciclos consecutivos falharam nos dois destinos TCP externos. Verifique outros dispositivos antes de concluir.");}
                else if(effective>=0&&outageAlertSent){outageAlertSent=false;postNetworkAlert("Conexão recuperada","Pelo menos um dos destinos TCP externos voltou a responder.");}
            });
        });
    }
    private long probe(String host,int port,int timeout){
        long start=System.nanoTime();try(Socket s=new Socket()){s.connect(new InetSocketAddress(host,port),timeout);return (System.nanoTime()-start)/1_000_000L;}catch(Exception e){return -1;}
    }
    private void updateTransport(){
        try{ConnectivityManager cm=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);Network n=cm.getActiveNetwork();NetworkCapabilities c=n==null?null:cm.getNetworkCapabilities(n);
            String type="Sem rede ativa";if(c!=null){if(c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))type="Ethernet";else if(c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))type="Wi-Fi";else if(c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))type="Dados móveis";else type="Outra conexão";}
            if(transport!=null)transport.setText("Rede: "+type);
        }catch(Exception e){if(transport!=null)transport.setText("Rede: indisponível");}
    }
    private void diagnoseLatest(){
        int n=history.size();if(n==0)return;Sample last=history.get(n-1);
        if(!last.internetOk){diagnostic.setText("As duas verificações TCP externas falharam (1.1.1.1:443 e 8.8.8.8:443). Pode haver falha de internet, bloqueio de saída ou indisponibilidade simultânea dos destinos. Compare com outro aparelho antes de concluir.");}
        else if(!last.primaryOk||!last.alternateOk){diagnostic.setText("Somente um dos dois destinos TCP respondeu. Isso pode ser uma falha específica de destino ou rota; não é evidência suficiente de queda geral. Cloudflare: "+(last.primaryOk?"OK":"falhou")+"; Google: "+(last.alternateOk?"OK":"falhou")+".");}
        else if(jitterEwma>35){diagnostic.setText("A variação absoluta suavizada entre amostras TCP está elevada ("+String.format(Locale.getDefault(),"%.1f",jitterEwma)+" ms). É um indicador aproximado, não jitter UDP/ICMP. Compare em Ethernet e Wi-Fi.");}
        else if(consecutiveFailures==0){diagnostic.setText("Os dois destinos TCP externos responderam. Até agora não há evidência suficiente para atribuir uma causa. Latência efetiva: "+last.effectiveLatency()+" ms.");}
        if(last.routerMs<0&&last.routerHttpsMs<0)diagnostic.append("\nO roteador não respondeu nas portas TCP 80 e 443. A interface pode usar outra porta ou bloquear conexões; isso não prova que ele esteja offline.");
    }
    private void renderHistory(){
        if(history.isEmpty())return;
        Sample s=history.get(history.size()-1);
        internet.setText(s.effectiveLatency()>=0?s.effectiveLatency()+" ms":"Falhou");
        router.setText(s.routerMs>=0?s.routerMs+" ms":(s.routerHttpsMs>=0?s.routerHttpsMs+" ms (HTTPS)":"Sem resposta"));
        jitter.setText(String.format(Locale.getDefault(),"%.1f ms",jitterEwma));
        double rate=totalChecks==0?0:(100.0*tcpFailures/totalChecks);
        failureRate.setText(String.format(Locale.getDefault(),"%.1f%%",rate));
        updated.setText("Última amostra: "+new SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale.getDefault()).format(new Date(s.time)));
        summary.setText("Amostras: "+history.size()+" · Falhas TCP: "+tcpFailures+" · Intervalo: "+(intervalMs/1000)+" s");
        graph.invalidate();updateTransport();
    }
    private void saveHistoryLocked(){
        try{JSONArray a=new JSONArray();for(Sample s:history){JSONObject o=new JSONObject();o.put("t",s.time);o.put("i",s.internetMs);o.put("a",s.alternateMs);o.put("r",s.routerMs);o.put("rh",s.routerHttpsMs);o.put("po",s.primaryOk);o.put("ao",s.alternateOk);o.put("ok",s.internetOk);a.put(o);}
            getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString("samples",a.toString()).putLong("checks",totalChecks).putLong("fails",tcpFailures).apply();
        }catch(Exception ignored){}
    }
    private void loadHistory(){
        try{String raw=getSharedPreferences(PREFS,MODE_PRIVATE).getString("samples","[]");JSONArray a=new JSONArray(raw);
            for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);long primary=o.getLong("i");boolean primaryOk=o.optBoolean("po",o.getBoolean("ok"));long alternate=o.optLong("a",-1);boolean alternateOk=o.optBoolean("ao",false);history.add(new Sample(o.getLong("t"),primary,alternate,o.getLong("r"),o.optLong("rh",-1),primaryOk,alternateOk));}
            totalChecks=getSharedPreferences(PREFS,MODE_PRIVATE).getLong("checks",history.size());
            tcpFailures=getSharedPreferences(PREFS,MODE_PRIVATE).getLong("fails",0);
            for(int i=history.size()-1;i>=0;i--)if(history.get(i).effectiveLatency()>=0){lastGoodLatency=history.get(i).effectiveLatency();break;}
            for(int i=1;i<history.size();i++){Sample a1=history.get(i-1),a2=history.get(i);if(a1.effectiveLatency()>=0&&a2.effectiveLatency()>=0){double d=Math.abs(a2.effectiveLatency()-a1.effectiveLatency());jitterEwma=(jitterEwma==0?d:0.25*d+0.75*jitterEwma);}}
        }catch(Exception ignored){}
    }
    private void exportCsv(){
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("text/csv");i.putExtra(Intent.EXTRA_TITLE,"netguard-historico.csv");startActivityForResult(i,42);
    }
    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);if(req==42&&result==RESULT_OK&&data!=null&&data.getData()!=null){
            try(OutputStream out=getContentResolver().openOutputStream(data.getData())){
                StringBuilder csv=new StringBuilder("timestamp,data_hora,cloudflare_tcp_ms,google_tcp_ms,roteador_http_tcp_ms,roteador_https_tcp_ms,internet_tcp_ok\n");
                SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US);
                synchronized(history){for(Sample s:history)csv.append(s.time).append(',').append(f.format(new Date(s.time))).append(',').append(s.internetMs).append(',').append(s.alternateMs).append(',').append(s.routerMs).append(',').append(s.routerHttpsMs).append(',').append(s.internetOk).append('\n');}
                if(out==null)throw new IllegalStateException("Destino indisponível");out.write(csv.toString().getBytes(StandardCharsets.UTF_8));Toast.makeText(this,"CSV exportado.",Toast.LENGTH_LONG).show();
            }catch(Exception e){Toast.makeText(this,"Falha ao exportar CSV.",Toast.LENGTH_LONG).show();}
        }
    }
    private void confirmClear(){
        new android.app.AlertDialog.Builder(this).setTitle("Apagar histórico?").setMessage("Todas as amostras locais serão apagadas. Esta ação não pode ser desfeita.")
            .setNegativeButton("Cancelar",null).setPositiveButton("Apagar",(d,w)->{synchronized(history){history.clear();totalChecks=0;tcpFailures=0;lastGoodLatency=-1;jitterEwma=0;consecutiveFailures=0;saveHistoryLocked();}internet.setText("-- ms");router.setText("-- ms");jitter.setText("-- ms");failureRate.setText("0%");updated.setText("Histórico apagado.");summary.setText("Aguardando novas amostras.");diagnostic.setText("Histórico local apagado.");graph.invalidate();}).show();
    }
    @Override protected void onDestroy(){running=false;if(loop!=null)handler.removeCallbacks(loop);worker.shutdownNow();scanWorker.shutdownNow();super.onDestroy();}

    private class Graph extends View{
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Graph(Context c){super(c);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight();p.setColor(Color.rgb(55,72,94));p.setStrokeWidth(dp(1));for(int i=1;i<4;i++)c.drawLine(0,h*i/4,w,h*i/4,p);
            ArrayList<Sample> copy; synchronized(history){copy=new ArrayList<>(history);}int count=Math.min(80,copy.size());if(count<2)return;
            long max=100;for(int i=copy.size()-count;i<copy.size();i++)if(copy.get(i).effectiveLatency()>max)max=copy.get(i).effectiveLatency();
            p.setColor(GREEN);p.setStrokeWidth(dp(2));p.setStyle(Paint.Style.STROKE);boolean prev=false;float px=0,py=0;
            for(int i=0;i<count;i++){Sample s=copy.get(copy.size()-count+i);if(s.effectiveLatency()<0){prev=false;continue;}float x=4+(w-8)*i/(count-1);float y=h-8-(float)Math.min(s.effectiveLatency(),max)/(float)max*(h-16);
                if(prev)c.drawLine(px,py,x,y,p);px=x;py=y;prev=true;}p.setStyle(Paint.Style.FILL);
        }
    }
}
