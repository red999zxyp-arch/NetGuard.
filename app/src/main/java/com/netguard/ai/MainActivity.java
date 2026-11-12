package com.netguard.ai;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Color;
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
import android.widget.TextView;
import android.widget.Toast;

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
    private static final int NAVY = Color.rgb(10, 20, 38);
    private static final int PANEL = Color.rgb(20, 34, 56);
    private static final int GREEN = Color.rgb(39, 205, 130);
    private static final int WHITE = Color.WHITE;
    private static final int MUTED = Color.rgb(163, 179, 199);
    private static final String PREFS = "netguard_history";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ArrayList<Sample> samples = new ArrayList<>();
    private TextView stateText, internetText, routerText, jitterText, failuresText, networkText, lastText;
    private GraphView graph;
    private Button startButton;
    private boolean monitoring = false;
    private long lastInternet = -1;
    private double jitter = 0;
    private int failures = 0;
    private Runnable tick;

    static class Sample {
        long time;
        long internetMs;
        long routerMs;
        boolean internetOk;
        Sample(long t, long i, long r, boolean ok) {
            time=t; internetMs=i; routerMs=r; internetOk=ok;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(NAVY);
        getWindow().setNavigationBarColor(NAVY);
        loadHistory();
        buildUi();
        refreshMetrics();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(NAVY);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(22), dp(20), dp(28));
        scroll.addView(root);

        TextView brand = text("NETGUARD AI", 25, WHITE, true);
        root.addView(brand);
        TextView subtitle = text("Monitoramento inteligente da sua conexão", 13, MUTED, false);
        root.addView(subtitle);

        LinearLayout stateCard = card();
        stateText = text("●  Monitoramento parado", 16, MUTED, true);
        stateCard.addView(stateText);
        networkText = text("Rede: verificando…", 13, MUTED, false);
        stateCard.addView(networkText);
        root.addView(stateCard);

        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        internetText = metricCard(metrics, "LATÊNCIA TCP", "--", "ms");
        routerText = metricCard(metrics, "ROTEADOR", "--", "ms");
        root.addView(metrics);

        LinearLayout second = new LinearLayout(this);
        second.setOrientation(LinearLayout.HORIZONTAL);
        jitterText = metricCard(second, "JITTER ESTIMADO", "--", "ms");
        failuresText = metricCard(second, "FALHAS DE TESTE", "0", "");
        root.addView(second);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        startButton = new Button(this);
        startButton.setText("Iniciar monitoramento");
        startButton.setTextColor(NAVY);
        startButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(GREEN));
        startButton.setOnClickListener(v -> toggleMonitoring());
        actions.addView(startButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button csv = new Button(this);
        csv.setText("Exportar CSV");
        csv.setOnClickListener(v -> exportCsv());
        actions.addView(csv, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(actions);

        LinearLayout chartCard = card();
        chartCard.addView(text("Histórico de latência", 16, WHITE, true));
        graph = new GraphView(this);
        chartCard.addView(graph, new LinearLayout.LayoutParams(-1, dp(170)));
        lastText = text("Nenhuma medição registrada.", 12, MUTED, false);
        chartCard.addView(lastText);
        root.addView(chartCard);

        LinearLayout info = card();
        info.addView(text("Como interpretar", 16, WHITE, true));
        info.addView(text("• Latência TCP: tempo para abrir uma conexão com 1.1.1.1:443. Não é ping ICMP.\n• Jitter: variação entre latências TCP consecutivas.\n• Falhas: tentativas TCP que não completaram; não equivalem à perda real de pacotes IP.\n• Roteador: tentativa TCP na porta 80 de 192.168.15.1; pode falhar se a interface não aceitar conexões.", 13, MUTED, false));
        root.addView(info);

        TextView foot = text("Medições feitas no próprio aparelho. O monitoramento ocorre enquanto esta tela permanece ativa.", 11, MUTED, false);
        foot.setPadding(dp(4), dp(12), dp(4), 0);
        root.addView(foot);
        setContentView(scroll);
    }

    private TextView metricCard(LinearLayout parent, String label, String value, String unit) {
        LinearLayout c = card();
        TextView title = text(label, 10, MUTED, true);
        c.addView(title);
        TextView valueView = text(value + (unit.isEmpty() ? "" : " " + unit), 24, WHITE, true);
        c.addView(valueView);
        parent.addView(c, new LinearLayout.LayoutParams(0, dp(94), 1));
        return valueView;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(13), dp(14), dp(13));
        c.setBackgroundColor(PANEL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(14), 0, 0);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(0, dp(3), 0, dp(3));
        return t;
    }

    private int dp(float value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }

    private void toggleMonitoring() {
        monitoring = !monitoring;
        if (monitoring) {
            startButton.setText("Parar monitoramento");
            stateText.setText("●  Monitoramento ativo");
            stateText.setTextColor(GREEN);
            tick = new Runnable() {
                @Override public void run() {
                    if (!monitoring) return;
                    runProbe();
                    handler.postDelayed(this, 10000);
                }
            };
            tick.run();
        } else {
            handler.removeCallbacks(tick);
            startButton.setText("Iniciar monitoramento");
            stateText.setText("●  Monitoramento parado");
            stateText.setTextColor(MUTED);
        }
    }

    private void runProbe() {
        networkText.setText("Rede: " + activeTransport() + " · testando…");
        executor.execute(() -> {
            long internet = probe("1.1.1.1", 443, 2500);
            long router = probe("192.168.15.1", 80, 1200);
            long now = System.currentTimeMillis();
            boolean ok = internet >= 0;
            synchronized (samples) {
                if (!ok) failures++;
                if (ok && lastInternet >= 0) jitter = Math.abs(internet - lastInternet);
                if (ok) lastInternet = internet;
                samples.add(new Sample(now, internet, router, ok));
                while (samples.size() > 1500) samples.remove(0);
                saveHistory();
            }
            runOnUiThread(() -> {
                internetText.setText(ok ? internet + " ms" : "Falhou");
                routerText.setText(router >= 0 ? router + " ms" : "Sem resposta");
                jitterText.setText(ok ? String.format(Locale.getDefault(), "%.1f ms", jitter) : "--");
                failuresText.setText(String.valueOf(failures));
                networkText.setText("Rede: " + activeTransport());
                lastText.setText("Última tentativa: " + new SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(new Date(now)));
                graph.invalidate();
            });
        });
    }

    private long probe(String host, int port, int timeout) {
        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeout);
            return (System.nanoTime() - start) / 1_000_000;
        } catch (Exception e) {
            return -1;
        }
    }

    private String activeTransport() {
        try {
            ConnectivityManager cm = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
            if (c == null) return "Sem rede ativa";
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Wi-Fi";
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "Dados móveis";
            return "Outra conexão";
        } catch (Exception e) { return "Indisponível"; }
    }

    private void saveHistory() {
        try {
            JSONArray array = new JSONArray();
            for (Sample s : samples) {
                JSONObject o = new JSONObject();
                o.put("time", s.time); o.put("internet", s.internetMs);
                o.put("router", s.routerMs); o.put("ok", s.internetOk);
                array.put(o);
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("samples", array.toString()).putInt("failures", failures).apply();
        } catch (Exception ignored) { }
    }

    private void loadHistory() {
        try {
            String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString("samples", "[]");
            JSONArray a = new JSONArray(raw);
            for (int i=0; i<a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                samples.add(new Sample(o.getLong("time"), o.getLong("internet"), o.getLong("router"), o.getBoolean("ok")));
            }
            failures = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("failures", 0);
            for (int i=samples.size()-1; i>=0; i--) if (samples.get(i).internetOk) { lastInternet=samples.get(i).internetMs; break; }
        } catch (Exception ignored) { }
    }

    private void refreshMetrics() {
        failuresText.setText(String.valueOf(failures));
        if (!samples.isEmpty()) {
            Sample s = samples.get(samples.size()-1);
            internetText.setText(s.internetOk ? s.internetMs + " ms" : "Falhou");
            routerText.setText(s.routerMs >= 0 ? s.routerMs + " ms" : "Sem resposta");
            graph.invalidate();
        }
        networkText.setText("Rede: " + activeTransport());
    }

    private void exportCsv() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, "netguard-historico.csv");
        startActivityForResult(intent, 42);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 42 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                StringBuilder csv = new StringBuilder("data_hora,latencia_tcp_ms,tempo_tcp_roteador_ms,conexao_internet_ok\n");
                SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                synchronized (samples) {
                    for (Sample s : samples) csv.append(fmt.format(new Date(s.time))).append(',')
                        .append(s.internetMs).append(',').append(s.routerMs).append(',').append(s.internetOk).append('\n');
                }
                out.write(csv.toString().getBytes(StandardCharsets.UTF_8));
                Toast.makeText(this, "CSV exportado.", Toast.LENGTH_LONG).show();
            } catch (Exception e) { Toast.makeText(this, "Não foi possível exportar o CSV.", Toast.LENGTH_LONG).show(); }
        }
    }

    @Override protected void onDestroy() {
        monitoring = false;
        if (tick != null) handler.removeCallbacks(tick);
        executor.shutdownNow();
        super.onDestroy();
    }

    private class GraphView extends View {
        private final Paint p = new Paint(3);
        GraphView(Context c) { super(c); setLayerType(View.LAYER_TYPE_SOFTWARE, null); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w=getWidth(), h=getHeight();
            p.setColor(Color.rgb(57,75,98)); p.setStrokeWidth(dp(1));
            for (int i=1;i<4;i++) canvas.drawLine(0,h*i/4,w,h*i/4,p);
            ArrayList<Sample> copy;
            synchronized (samples) { copy = new ArrayList<>(samples); }
            int count=Math.min(60,copy.size());
            if (count<2) return;
            long max=1;
            for (int i=copy.size()-count;i<copy.size();i++) if (copy.get(i).internetMs>max) max=copy.get(i).internetMs;
            max=Math.max(max,100);
            p.setColor(GREEN); p.setStrokeWidth(dp(2)); p.setStyle(Paint.Style.STROKE);
            float prevX=0,prevY=0; boolean prev=false;
            for (int i=0;i<count;i++) {
                Sample s=copy.get(copy.size()-count+i);
                if (!s.internetOk) { prev=false; continue; }
                float x=(count==1?0:(w-8)*i/(count-1))+4;
                float y=h-8-(float)Math.min(s.internetMs,max)/max*(h-16);
                if (prev) canvas.drawLine(prevX,prevY,x,y,p);
                prevX=x;prevY=y;prev=true;
            }
            p.setStyle(Paint.Style.FILL);
        }
    }
}
