package com.netguard.ai;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Read-only inventory of reachable LAN hosts. Does not authenticate or change devices. */
final class LanScanner {
    static final class Device {
        final String ip, mac, services;
        Device(String ip,String mac,String services){this.ip=ip;this.mac=mac;this.services=services;}
    }
    interface Callback { void onComplete(String subnet,List<Device> devices,String error); }
    private LanScanner(){}

    static void scan(Context context, Callback callback) {
        ExecutorService pool=Executors.newFixedThreadPool(32);
        try {
            ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network active=cm==null?null:cm.getActiveNetwork();
            LinkProperties lp=active==null?null:cm.getLinkProperties(active);
            Inet4Address local=null; int prefix=-1;
            if(lp!=null) for(android.net.LinkAddress a:lp.getLinkAddresses())
                if(a.getAddress() instanceof Inet4Address){local=(Inet4Address)a.getAddress();prefix=a.getPrefixLength();break;}
            if(local==null||prefix<16||prefix>30){callback.onComplete("",Collections.emptyList(),"Não foi possível identificar uma sub-rede IPv4 local válida.");return;}
            byte[] b=local.getAddress(); long ip=((b[0]&255L)<<24)|((b[1]&255L)<<16)|((b[2]&255L)<<8)|(b[3]&255L);
            long mask=(0xffffffffL << (32-prefix))&0xffffffffL, network=ip&mask, broadcast=network|(~mask&0xffffffffL);
            if(broadcast-network>512){callback.onComplete("",Collections.emptyList(),"A rede é grande demais para uma busca segura nesta versão.");return;}
            Map<String,String> found=new ConcurrentHashMap<>();
            List<Runnable> jobs=new ArrayList<>();
            for(long n=network+1;n<broadcast;n++){
                if(n==ip)continue;
                final String host=((n>>24)&255)+"."+((n>>16)&255)+"."+((n>>8)&255)+"."+(n&255);
                jobs.add(()->{
                    StringBuilder open=new StringBuilder();
                    int[] ports={80,443,554,8080};
                    for(int port:ports) try(Socket s=new Socket()){
                        s.connect(new InetSocketAddress(host,port),220);
                        if(open.length()>0)open.append(", ");open.append(port);
                    }catch(Exception ignored){}
                    if(open.length()>0)found.put(host,open.toString());
                    else try(Socket s=new Socket()){s.connect(new InetSocketAddress(host,9),160);}catch(Exception ignored){}
                });
            }
            for(Runnable r:jobs)pool.execute(r);
            pool.shutdown();pool.awaitTermination(45,TimeUnit.SECONDS);
            Map<String,String> arp=readArp();
            TreeMap<String,Device> result=new TreeMap<>();
            for(Map.Entry<String,String> e:arp.entrySet()){
                String ipAddress=e.getKey(), mac=e.getValue();
                if(ipAddress.startsWith("0.")||mac.equals("00:00:00:00:00:00"))continue;
                result.put(ipAddress,new Device(ipAddress,mac,found.getOrDefault(ipAddress,"Sem porta testada aberta")));
            }
            for(Map.Entry<String,String> e:found.entrySet()) result.putIfAbsent(e.getKey(),new Device(e.getKey(),"MAC indisponível",e.getValue()));
            callback.onComplete(InetAddress.getByAddress(toBytes(network)).getHostAddress()+"/"+prefix,new ArrayList<>(result.values()),null);
        } catch(Exception e){pool.shutdownNow();callback.onComplete("",Collections.emptyList(),"Falha na busca local: "+e.getClass().getSimpleName());}
    }
    private static byte[] toBytes(long n){return new byte[]{(byte)(n>>24),(byte)(n>>16),(byte)(n>>8),(byte)n};}
    private static Map<String,String> readArp(){
        Map<String,String> m=new TreeMap<>();
        try(BufferedReader r=new BufferedReader(new FileReader("/proc/net/arp"))){
            String line; r.readLine();
            while((line=r.readLine())!=null){String[] p=line.trim().split("\\s+");if(p.length>=4)m.put(p[0],p[3].toLowerCase());}
        }catch(Exception ignored){}
        return m;
    }
}
