package kr.lane.guide;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.speech.tts.TextToSpeech;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/** 화면이 꺼지거나 다른 앱(티맵)이 앞에 있어도 GPS로 진행 위치를 계산해 합류 지점 전에 음성 안내 */
public class GuideService extends Service implements LocationListener, TextToSpeech.OnInitListener {
    static final String ACTION_START = "start", ACTION_STOP = "stop";
    static final String CH = "guide";
    static final int NID = 7;
    static volatile String pending;
    static volatile boolean running;
    static final java.util.LinkedList<String> LOG = new java.util.LinkedList<>();
    static android.content.SharedPreferences sp;
    static synchronized void log(String m) {
        String t = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA).format(new java.util.Date());
        LOG.addFirst(t + " " + m); while (LOG.size() > 300) LOG.removeLast();
        if (sp != null) sp.edit().putString("log", android.text.TextUtils.join("\n", LOG)).apply();
    }
    static synchronized String dump(android.content.Context c) {
        if (LOG.isEmpty()) { String s = c.getSharedPreferences("lg", MODE_PRIVATE).getString("log", ""); if (!s.isEmpty()) java.util.Collections.addAll(LOG, s.split("\n")); }
        return android.text.TextUtils.join("\n", LOG);
    }
    private long lastFixLog;
    private volatile boolean stopped;
    static final String ACTION_FREE = "free";
    private boolean free; private double[][] allIc; private String[] allIcName;
    private final Map<String, Long> freeDone = new HashMap<>(); private final Set<String> freeReq = new HashSet<>();
    private final Map<String, Integer> freeDec = new java.util.concurrent.ConcurrentHashMap<>(); // 1=혼잡 0=원활 -1=고속도로 아님
    private volatile int lastNMain;
    private double[] lastPt; private double lastHd = -1; private int offCount;
    private volatile boolean requireMain; // 목적지 없는 모드: 내 방향 고속도로 본선이 확인될 때만 안내

    private double[][] poly; private double[] cum;
    private String[] tName; private double[] tAlong;
    private boolean sayClear = true;
    private double distM = 2000; private String phrase = "{IC} 진입 차량 많은 구간입니다. 왼쪽 차로로 이동하세요.";
    private final Set<Integer> announced = new HashSet<>();
    private final Map<Integer, Boolean> decision = new java.util.concurrent.ConcurrentHashMap<>(); // 없음=조회중
    private final Set<Integer> requested = new HashSet<>();
    private double[][] tGeo; private String tmapKey = "";
    private static final String[] CONG = {"정보없음","원활","서행","지체","정체"};
    private TextToSpeech tts; private boolean ttsReady; private String queued;
    private LocationManager lm;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { stopAll(); return START_NOT_STICKY; }
        if (intent == null) { stopAll(); return START_NOT_STICKY; } // 시스템 재시작 시 데이터 없음 → 종료
        stopped = false; running = true;
        if (sp == null) sp = getSharedPreferences("lg", MODE_PRIVATE); dump(this);
        log("시작: " + (ACTION_FREE.equals(intent.getAction()) ? "목적지 없음 모드" : "목적지 경로 모드"));
        startFg("안내 준비 중");
        free = intent != null && ACTION_FREE.equals(intent.getAction());
        try { if (free) loadFree(pending); else load(pending); } catch (Exception e) { update("데이터 오류"); }
        if (tts == null) tts = new TextToSpeech(this, this);
        say(free ? "목적지 없이 정체 안내를 시작합니다" : "백그라운드 안내를 시작합니다");
        startGps();
        return START_NOT_STICKY;
    }

    private void load(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        JSONArray p = o.getJSONArray("poly"), c = o.getJSONArray("cum"), t = o.getJSONArray("targets");
        poly = new double[p.length()][2];
        for (int i = 0; i < p.length(); i++) { JSONArray q = p.getJSONArray(i); poly[i][0] = q.getDouble(0); poly[i][1] = q.getDouble(1); }
        cum = new double[c.length()];
        for (int i = 0; i < c.length(); i++) cum[i] = c.getDouble(i);
        tName = new String[t.length()]; tAlong = new double[t.length()];
        for (int i = 0; i < t.length(); i++) { JSONObject x = t.getJSONObject(i); tName[i] = x.getString("name"); tAlong[i] = x.getDouble("along"); }
        JSONArray g = o.optJSONArray("targetsGeo");
        tGeo = new double[tName.length][2];
        if (g != null) for (int i = 0; i < g.length() && i < tGeo.length; i++) { tGeo[i][0] = g.getJSONArray(i).getDouble(0); tGeo[i][1] = g.getJSONArray(i).getDouble(1); }
        tmapKey = o.optString("tmapKey", "");
        decision.clear(); requested.clear(); offCount = 0; lastPt = null; lastHd = -1;
        JSONArray ia = o.optJSONArray("ics");
        if (ia != null) {
            allIc = new double[ia.length()][2]; allIcName = new String[ia.length()];
            for (int i = 0; i < ia.length(); i++) { JSONArray q = ia.getJSONArray(i); allIcName[i] = q.getString(0); allIc[i][0] = q.getDouble(1); allIc[i][1] = q.getDouble(2); }
        }
        distM = o.optDouble("dist", 2000);
        phrase = o.optString("phrase", phrase);
        sayClear = o.optBoolean("sayClear", true);
        announced.clear();
    }

    private void loadFree(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        JSONArray a = o.getJSONArray("ics");
        allIc = new double[a.length()][2]; allIcName = new String[a.length()];
        for (int i = 0; i < a.length(); i++) { JSONArray q = a.getJSONArray(i); allIcName[i] = q.getString(0); allIc[i][0] = q.getDouble(1); allIc[i][1] = q.getDouble(2); }
        tmapKey = o.optString("tmapKey", ""); distM = o.optDouble("dist", 2000); phrase = o.optString("phrase", phrase); sayClear = o.optBoolean("sayClear", true);
        freeDone.clear(); freeReq.clear(); freeDec.clear(); poly = null; lastPt = null; lastHd = -1;
    }

    private static double hav(double lon1, double lat1, double lon2, double lat2) {
        double k = Math.PI / 180, dLat = (lat2 - lat1) * k, dLon = (lon2 - lon1) * k;
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(lat1 * k) * Math.cos(lat2 * k) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * 6371000 * Math.asin(Math.sqrt(h));
    }
    private static double[] offset(double[] p, double brg, double m) {
        double k = Math.PI / 180;
        return new double[]{p[0] + m * Math.sin(brg * k) / (111320 * Math.cos(p[1] * k)), p[1] + m * Math.cos(brg * k) / 110540};
    }

    /** 목적지 없는 모드: 진행 방향 앞쪽 IC를 찾아 혼잡하면 안내 */
    private void onFree(Location loc) {
        double kmh = loc.hasSpeed() ? loc.getSpeed() * 3.6 : 0;
        double[] me = {loc.getLongitude(), loc.getLatitude()};
        // 진행 방향: 빠를 땐 GPS 방향값, 느릴 땐 30m 이상 이동한 위치 변화로 계산 (정체 중에도 동작)
        if (loc.hasBearing() && kmh >= 15) lastHd = loc.getBearing();
        if (lastPt == null) lastPt = me;
        else if (hav(lastPt[0], lastPt[1], me[0], me[1]) >= 30) { if (kmh < 15) lastHd = bearing(lastPt, me); lastPt = me; }
        if (lastHd < 0) { update("진행 방향 확인 중 (조금 이동하면 시작)"); if (System.currentTimeMillis() - lastFixLog > 60000) { lastFixLog = System.currentTimeMillis(); log("위치 수신, 진행 방향 계산 대기"); } return; }
        double hd = lastHd;
        int best = -1; double bestD = 1e9;
        for (int i = 0; i < allIc.length; i++) {
            double d = hav(me[0], me[1], allIc[i][0], allIc[i][1]);
            if (d > 3500 || d < 150) continue;
            if (angDiff(bearing(me, allIc[i]), hd) > 25) continue;
            if (d < bestD) { bestD = d; best = i; }
        }
        long now = System.currentTimeMillis();
        if (best < 0) { update(String.format(Locale.KOREA, "주행 중 %.0fkm/h · 앞쪽 합류 지점 없음", kmh)); if (now - lastFixLog > 60000) { lastFixLog = now; log(String.format(Locale.KOREA, "위치 수신 중 %.0fkm/h 방향 %.0f° · 3.5km 내 앞쪽 IC 없음", kmh, hd)); } return; }
        final String key = allIcName[best]; Long done = freeDone.get(key);
        if (done != null && now - done < 15 * 60 * 1000) { update(key + " 통과 중"); return; }
        if (bestD <= distM + 800 && !freeReq.contains(key)) {
            freeReq.add(key);
            final double[] ic = allIc[best];
            // 내 진행 방향으로 뻗은 선(뒤 300m ~ 앞 IC거리+600m)을 임시 경로로 보고 판정
            final double[][] pl = {offset(me, (hd + 180) % 360, 300), me, offset(me, hd, bestD + 600)};
            final double icA = 300 + bestD;
            log(String.format(Locale.KOREA, "대상 %s %.1fkm 앞, 방향 %.0f°, %.0fkm/h → 교통정보 조회", key, bestD / 1000, hd, kmh));
            new Thread(() -> { boolean a = judgeFree(pl, ic, icA); int v = a ? 1 : (lastNMain > 0 ? 0 : -1); freeDec.put(key, v);
                log(key + " 판정: " + lastSummary + " → " + (v == 1 ? "혼잡(안내)" : v == 0 ? "원활" : "고속도로 본선 미확인")); }).start();
        }
        Integer dec = freeDec.get(key);
        update(String.format(Locale.KOREA, "%s%s %.1fkm 앞 · %.0fkm/h%s", free ? "" : "[경로 이탈·진행방향 기준] ", key, bestD / 1000, kmh, dec == null ? (freeReq.contains(key) ? " · 확인 중" : "") : dec == 1 ? " · 혼잡→안내" : dec == 0 ? " · 원활" : " · 고속도로 아님"));
        if (bestD <= distM && dec != null) {
            freeDone.put(key, now); freeReq.remove(key); freeDec.remove(key);
            String sp = key.replaceAll("JCT$", " 분기점").replaceAll("IC$", " 나들목");
            if (dec == 1) say(phrase.replace("{IC}", sp)); else if (dec == 0 && sayClear) say(sp + " 합류 원활"); else log(key + " 무음 처리 (" + (dec == -1 ? "고속도로 아님" : "원활·알림 꺼짐") + ")");
        }
    }

    private void startGps() {
        if (lm != null) return;
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, Looper.getMainLooper());
            log("GPS 수신 시작");
        } catch (SecurityException e) { update("위치 권한 없음"); log("위치 권한 없음"); }
    }

    @Override
    public void onLocationChanged(Location loc) {
        if (stopped) return;
        if (free) { onFree(loc); return; }
        if (poly == null || poly.length < 2) return;
        double[] pr = project(loc.getLongitude(), loc.getLatitude());
        if (pr[0] > 250) {
            offCount++;
            if (offCount == 5) log("경로 이탈 → 진행 방향 기준으로 전환");
            if (offCount >= 5 && allIc != null) { onFree(loc); return; } // 경로 이탈 → 실제 진행 방향 기준
            update(String.format(Locale.KOREA, "경로에서 %.0fm 벗어남", pr[0])); return;
        }
        offCount = 0;
        double along = pr[1];
        int next = -1;
        for (int i = 0; i < tAlong.length; i++) if (tAlong[i] > along - 300) { next = i; break; }
        if (next < 0) { update("남은 합류 지점 없음"); return; }
        double gap = tAlong[next] - along;
        final int idx = next;
        if (gap <= distM + 800 && !requested.contains(idx)) { requested.add(idx); new Thread(() -> judge(idx)).start(); }
        Boolean dec = decision.get(idx);
        String st = !requested.contains(idx) ? "" : dec == null ? " · 확인 중" : dec ? " · 혼잡→안내" : " · 원활→생략";
        update(String.format(Locale.KOREA, "%s까지 %.1fkm%s", tName[next], gap / 1000, st));
        if (gap <= distM && gap > 200 && !announced.contains(next) && (dec != null || gap < distM - 500)) {
            announced.add(next);
            if (dec != null && !dec) { if (sayClear) say(tName[next] + " 합류 원활"); return; }
            say(phrase.replace("{IC}", tName[next]));
        }
    }
    @Override public void onStatusChanged(String p, int s, Bundle e) {}
    @Override public void onProviderEnabled(String p) {}
    @Override public void onProviderDisabled(String p) { update("GPS 꺼짐"); }

    /** 티맵 실시간 교통정보로 합류부 혼잡 판정 — 웹앱 checkMerge()와 같은 규칙 */
    private void judge(int i) { boolean a = judgeOn(null, tGeo[i], tAlong[i]); decision.put(i, a); log(tName[i] + " 판정(경로): " + lastSummary + " → " + (a ? "안내" : "원활")); }

    private synchronized boolean judgeFree(double[][] pl, double[] icPt, double icA) {
        requireMain = true;
        try {
            double[] cm = new double[pl.length];
            for (int k = 1; k < pl.length; k++) cm[k] = cm[k - 1] + hav(pl[k - 1][0], pl[k - 1][1], pl[k][0], pl[k][1]);
            return judgeOn(new Object[]{pl, cm}, icPt, icA);
        } finally { requireMain = false; }
    }
    private volatile String lastSummary = "";

    private boolean judgeOn(double[][] pl, double[] icPt) {
        double[] cm = new double[pl.length];
        for (int k = 1; k < pl.length; k++) cm[k] = cm[k - 1] + hav(pl[k - 1][0], pl[k - 1][1], pl[k][0], pl[k][1]);
        return judgeOn(new Object[]{pl, cm}, icPt, cm[1]);
    }

    /** route==null 이면 현재 경로(poly,cum) 사용 */
    private boolean judgeOn(Object[] route, double[] icPt, double icA) {
        final double[][] P = route == null ? poly : (double[][]) route[0];
        final double[] C = route == null ? cum : (double[]) route[1];
        boolean ann = true;
        try {
            String u = "https://apis.openapi.sk.com/tmap/traffic?version=1&format=json&reqCoordType=WGS84GEO&resCoordType=WGS84GEO&trafficType=AUTO&zoomLevel=16"
                    + "&centerLat=" + icPt[1] + "&centerLon=" + icPt[0];
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setRequestProperty("appKey", tmapKey); c.setRequestProperty("accept", "application/json");
            c.setConnectTimeout(8000); c.setReadTimeout(15000);
            if (c.getResponseCode() != 200) throw new Exception("http " + c.getResponseCode());
            InputStream in = c.getInputStream(); ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384]; int n; while ((n = in.read(buf)) > 0) bo.write(buf, 0, n); in.close();
            JSONArray fs = new JSONObject(bo.toString("UTF-8")).optJSONArray("features");
            int main = 0, ramp = 0, nMain = 0, nRamp = 0;
            for (int k = 0; fs != null && k < fs.length(); k++) {
                JSONObject f = fs.getJSONObject(k), geo = f.getJSONObject("geometry"), p = f.getJSONObject("properties");
                if (!"LineString".equals(geo.optString("type"))) continue;
                JSONArray cs = geo.getJSONArray("coordinates"); int m = cs.length(); if (m < 2) continue;
                double[] a = pt(cs, 0), b = pt(cs, m - 1), b2 = pt(cs, m - 2);
                int cong = p.optInt("congestion", 0);
                double[] s = projectSeg(P, C, a[0], a[1]), e = projectSeg(P, C, b[0], b[1]);
                int sg = (int) Math.min(e[2], P.length - 2);
                double rb = bearing(P[sg], P[sg + 1]);
                double tolM = requireMain ? 120 : 40, tolR = requireMain ? 150 : 80;
                if ("001".equals(p.optString("roadType")) && s[0] < tolM && e[0] < tolM) {
                    if (angDiff(bearing(a, b), rb) > (requireMain ? 30 : 60) || e[1] < s[1]) continue;
                    if (e[1] < icA - 1500 || s[1] > icA + 300) continue;
                    nMain++; main = Math.max(main, cong);
                } else if (e[0] < tolR && s[0] > tolR && Math.abs(e[1] - icA) < 1500 && angDiff(bearing(b2, b), rb) < 70) {
                    nRamp++; ramp = Math.max(ramp, cong);
                }
            }
            lastNMain = nMain;
            lastSummary = "본선 " + nMain + "개(최고 " + CONG[main] + "), 램프 " + nRamp + "개(최고 " + CONG[ramp] + "), 링크 " + (fs == null ? 0 : fs.length());
            if (requireMain) ann = nMain > 0 && (main >= 2 || ramp >= 2); // 고속도로 본선 미확인이면 안내 안 함
            else if (nMain + nRamp > 0) ann = main >= 2 || ramp >= 2;
        } catch (Exception ex) { ann = !requireMain; lastNMain = 0; lastSummary = "조회 실패: " + ex.getMessage(); log("교통정보 조회 실패: " + ex.getMessage()); }
        return ann;
    }

    private static double[] pt(JSONArray cs, int i) throws Exception { JSONArray q = cs.getJSONArray(i); return new double[]{q.getDouble(0), q.getDouble(1)}; }
    private static double bearing(double[] a, double[] b) {
        double k = Math.PI / 180, y = Math.sin((b[0] - a[0]) * k) * Math.cos(b[1] * k);
        double x = Math.cos(a[1] * k) * Math.sin(b[1] * k) - Math.sin(a[1] * k) * Math.cos(b[1] * k) * Math.cos((b[0] - a[0]) * k);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }
    private static double angDiff(double a, double b) { double d = Math.abs(a - b) % 360; return d > 180 ? 360 - d : d; }

    /** 반환 {수직거리m, 누적거리m} — 웹앱 project()와 같은 계산 */
    private double[] project(double lon, double lat) { double[] r = projectSeg(poly, cum, lon, lat); return new double[]{r[0], r[1]}; }

    /** {수직거리, 누적거리, 세그먼트} */
    private static double[] projectSeg(double[][] poly, double[] cum, double lon, double lat) {
        final double R = 6371000, k = Math.PI / 180, lat0 = poly[0][1];
        final double cx = k * R * Math.cos(lat0 * k), cy = k * R;
        double px = lon * cx, py = lat * cy, bestD = Double.MAX_VALUE, bestA = 0; int bestS = 0;
        for (int i = 0; i < poly.length - 1; i++) {
            double ax = poly[i][0] * cx, ay = poly[i][1] * cy, bx = poly[i + 1][0] * cx, by = poly[i + 1][1] * cy;
            double vx = bx - ax, vy = by - ay, L2 = vx * vx + vy * vy;
            double t = L2 == 0 ? 0 : ((px - ax) * vx + (py - ay) * vy) / L2;
            t = Math.max(0, Math.min(1, t));
            double d = Math.hypot(px - (ax + t * vx), py - (ay + t * vy));
            if (d < bestD) { bestD = d; bestA = cum[i] + t * (cum[i + 1] - cum[i]); bestS = i; }
        }
        return new double[]{bestD, bestA, bestS};
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) log("음성엔진 초기화 실패: " + status);
        if (status == TextToSpeech.SUCCESS) {
            int lr = tts.setLanguage(Locale.KOREAN);
            log("음성엔진 준비, 한국어 " + (lr >= 0 ? "사용 가능" : "없음(코드 " + lr + ")"));
            ttsReady = true;
            if (queued != null) { say(queued); queued = null; }
        }
    }

    private void say(String s) {
        if (stopped) return;
        if (!ttsReady) { queued = s; return; }
        int r = tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "g" + System.currentTimeMillis());
        log("음성: " + s + (r == TextToSpeech.SUCCESS ? "" : " (출력 실패 " + r + ")"));
    }

    private Notification build(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(new NotificationChannel(CH, "합류 안내", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, GuideService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        return nb.setContentTitle("합류 차로 안내 중").setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_directions).setOngoing(true)
                .setContentIntent(open).addAction(new Notification.Action.Builder(null, "종료", stop).build()).build();
    }

    private void startFg(String text) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, build(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        else startForeground(NID, build(text));
    }

    private void update(String text) {
        if (stopped) return;
        getSystemService(NotificationManager.class).notify(NID, build(text));
    }

    private void stopAll() {
        stopped = true; running = false; log("종료");
        if (lm != null) { lm.removeUpdates(this); lm = null; }
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; ttsReady = false; }
        stopForeground(true);
        getSystemService(NotificationManager.class).cancel(NID);
        stopSelf();
    }

    @Override public void onDestroy() {
        stopped = true; running = false;
        getSystemService(NotificationManager.class).cancel(NID);
        if (lm != null) lm.removeUpdates(this);
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
