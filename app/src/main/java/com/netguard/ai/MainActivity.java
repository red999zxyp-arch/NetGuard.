package com.netguard.ai;

import android.app.Activity;
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
    private final ArrayList<Sample> history=new ArrayList<>();
    private boolean running=false, gameMode=false, probeInFlight=false;
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
        final long time, internetMs, routerMs;
        final boolean internetOk;
        Sample(long t,long i,long r,boolean ok){time=t;internetMs=i;routerMs=r;internetOk=ok;}
    }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(NAVY);
        getWindow().setNavigationBarColor(NAVY);
        loadHistory();
        buildUi();
        renderHistory();
        updateTransport();
    }

    private void buildUi() {
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(NAVY);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18),dp(22),dp(18),dp(28)); scroll.addView(root);

        TextView brand=txt("NETGUARD AI",25,WHITE,true); root.addView(brand);
        root.addView(txt("Diagnóstico e estabilidade da conexão",13,MUTED,false));
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
        interval.setSelection(1);
        interval.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){intervalMs=new int[]{5000,10000,30000,60000}[pos];}
            public void onNothingSelected(android.widget.AdapterView<?> p){}
        });
        controls.addView(interval);
        Button scan=button("Buscar dispositivos na rede",GREEN,NAVY);
        scan.setOnClickListener(v->scanDevices(scan));
        controls.addView(scan);
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

        root.addView(txt("Importante: latência TCP não é ping ICMP. Falhas TCP não são perda de pacotes IP. A resposta na porta 80 do roteador não comprova que ele esteja online ou offline.",11,MUTED,false));
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
        LanScanner.scan(this,(subnet,found,error)->{
            button.setEnabled(true);
            if(error!=null){devices.setText("Busca indisponível: "+error);return;}
            StringBuilder out=new StringBuilder("Rede detectada: "+subnet+"\\nHosts encontrados: "+found.size());
            for(LanScanner.Device d:found){
                out.append("\\n").append(d.ip).append(" · ").append(d.mac)
                   .append(" · portas: ").append(d.services);
            }
            devices.setText(out.toString());
        });
    }
    private void toggleMode(){gameMode=!gameMode;modeButton.setText(gameMode?"Modo: Jogos":"Modo: Geral");diagnostic.setText(gameMode?"Perfil Rocket League: priorizando estabilidade e observação de variações. O teste do celular não mede o ping do jogo no outro dispositivo.":"Perfil geral: observação da conectividade externa e do acesso TCP ao roteador.");}

    private void runProbe(){
        if(probeInFlight)return; probeInFlight=true; updateTransport();
        worker.execute(()->{
            long net=probe("1.1.1.1",443,3000);
            long gw=probe("192.168.15.1",80,1500);
            long now=System.currentTimeMillis(); boolean ok=net>=0;
            synchronized(history){
                totalChecks++; if(!ok){tcpFailures++;consecutiveFailures++;}else consecutiveFailures=0;
                if(ok&&lastGoodLatency>=0){double delta=Math.abs(net-lastGoodLatency);jitterEwma=(jitterEwma==0?delta:(0.25*delta+0.75*jitterEwma));}
                if(ok)lastGoodLatency=net;
                history.add(new Sample(now,net,gw,ok));while(history.size()>2000)history.remove(0);
                saveHistoryLocked();
            }
            runOnUiThread(()->{probeInFlight=false;renderHistory();diagnoseLatest();});
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
        if(!last.internetOk){diagnostic.setText("A última tentativa TCP para 1.1.1.1:443 falhou. Isso pode indicar indisponibilidade do destino, bloqueio/instabilidade no caminho ou falta de internet. Repita e compare com outros dispositivos antes de concluir.");}
        else if(jitterEwma>35){diagnostic.setText("A variação absoluta suavizada entre amostras TCP está elevada ("+String.format(Locale.getDefault(),"%.1f",jitterEwma)+" ms). É um indicador aproximado, não jitter UDP/ICMP. Compare em Ethernet e Wi-Fi.");}
        else if(consecutiveFailures==0){diagnostic.setText("Último teste TCP concluído. Até agora não há evidência suficiente para atribuir uma causa. Latência atual: "+last.internetMs+" ms.");}
        if(last.routerMs<0)diagnostic.append("\nO teste TCP à porta 80 de 192.168.15.1 não respondeu; isso não prova que o roteador esteja desligado.");
    }
    private void renderHistory(){
        if(history.isEmpty())return;
        Sample s=history.get(history.size()-1);
        internet.setText(s.internetOk?s.internetMs+" ms":"Falhou");
        router.setText(s.routerMs>=0?s.routerMs+" ms":"Sem resposta");
        jitter.setText(String.format(Locale.getDefault(),"%.1f ms",jitterEwma));
        double rate=totalChecks==0?0:(100.0*tcpFailures/totalChecks);
        failureRate.setText(String.format(Locale.getDefault(),"%.1f%%",rate));
        updated.setText("Última amostra: "+new SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale.getDefault()).format(new Date(s.time)));
        summary.setText("Amostras: "+history.size()+" · Falhas TCP: "+tcpFailures+" · Intervalo: "+(intervalMs/1000)+" s");
        graph.invalidate();updateTransport();
    }
    private void saveHistoryLocked(){
        try{JSONArray a=new JSONArray();for(Sample s:history){JSONObject o=new JSONObject();o.put("t",s.time);o.put("i",s.internetMs);o.put("r",s.routerMs);o.put("ok",s.internetOk);a.put(o);}
            getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString("samples",a.toString()).putLong("checks",totalChecks).putLong("fails",tcpFailures).apply();
        }catch(Exception ignored){}
    }
    private void loadHistory(){
        try{String raw=getSharedPreferences(PREFS,MODE_PRIVATE).getString("samples","[]");JSONArray a=new JSONArray(raw);
            for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);history.add(new Sample(o.getLong("t"),o.getLong("i"),o.getLong("r"),o.getBoolean("ok")));}
            totalChecks=getSharedPreferences(PREFS,MODE_PRIVATE).getLong("checks",history.size());
            tcpFailures=getSharedPreferences(PREFS,MODE_PRIVATE).getLong("fails",0);
            for(int i=history.size()-1;i>=0;i--)if(history.get(i).internetOk){lastGoodLatency=history.get(i).internetMs;break;}
            for(int i=1;i<history.size();i++){Sample a1=history.get(i-1),a2=history.get(i);if(a1.internetOk&&a2.internetOk){double d=Math.abs(a2.internetMs-a1.internetMs);jitterEwma=(jitterEwma==0?d:0.25*d+0.75*jitterEwma);}}
        }catch(Exception ignored){}
    }
    private void exportCsv(){
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("text/csv");i.putExtra(Intent.EXTRA_TITLE,"netguard-historico.csv");startActivityForResult(i,42);
    }
    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);if(req==42&&result==RESULT_OK&&data!=null&&data.getData()!=null){
            try(OutputStream out=getContentResolver().openOutputStream(data.getData())){
                StringBuilder csv=new StringBuilder("timestamp,data_hora,latencia_tcp_ms,tempo_tcp_roteador_ms,tcp_internet_ok\n");
                SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US);
                synchronized(history){for(Sample s:history)csv.append(s.time).append(',').append(f.format(new Date(s.time))).append(',').append(s.internetMs).append(',').append(s.routerMs).append(',').append(s.internetOk).append('\n');}
                if(out==null)throw new IllegalStateException("Destino indisponível");out.write(csv.toString().getBytes(StandardCharsets.UTF_8));Toast.makeText(this,"CSV exportado.",Toast.LENGTH_LONG).show();
            }catch(Exception e){Toast.makeText(this,"Falha ao exportar CSV.",Toast.LENGTH_LONG).show();}
        }
    }
    private void confirmClear(){
        new android.app.AlertDialog.Builder(this).setTitle("Apagar histórico?").setMessage("Todas as amostras locais serão apagadas. Esta ação não pode ser desfeita.")
            .setNegativeButton("Cancelar",null).setPositiveButton("Apagar",(d,w)->{synchronized(history){history.clear();totalChecks=0;tcpFailures=0;lastGoodLatency=-1;jitterEwma=0;consecutiveFailures=0;saveHistoryLocked();}internet.setText("-- ms");router.setText("-- ms");jitter.setText("-- ms");failureRate.setText("0%");updated.setText("Histórico apagado.");summary.setText("Aguardando novas amostras.");diagnostic.setText("Histórico local apagado.");graph.invalidate();}).show();
    }
    @Override protected void onDestroy(){running=false;if(loop!=null)handler.removeCallbacks(loop);worker.shutdownNow();super.onDestroy();}

    private class Graph extends View{
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Graph(Context c){super(c);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight();p.setColor(Color.rgb(55,72,94));p.setStrokeWidth(dp(1));for(int i=1;i<4;i++)c.drawLine(0,h*i/4,w,h*i/4,p);
            ArrayList<Sample> copy; synchronized(history){copy=new ArrayList<>(history);}int count=Math.min(80,copy.size());if(count<2)return;
            long max=100;for(int i=copy.size()-count;i<copy.size();i++)if(copy.get(i).internetMs>max)max=copy.get(i).internetMs;
            p.setColor(GREEN);p.setStrokeWidth(dp(2));p.setStyle(Paint.Style.STROKE);boolean prev=false;float px=0,py=0;
            for(int i=0;i<count;i++){Sample s=copy.get(copy.size()-count+i);if(!s.internetOk){prev=false;continue;}float x=4+(w-8)*i/(count-1);float y=h-8-(float)Math.min(s.internetMs,max)/(float)max*(h-16);
                if(prev)c.drawLine(px,py,x,y,p);px=x;py=y;prev=true;}p.setStyle(Paint.Style.FILL);
        }
    }
}
