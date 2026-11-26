package com.abhisek.portfolioalerts;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads the plan (data/portfolio.json in the GitHub repo), fetches live prices,
 * works out a Buy / Wait / Hold / Buy more / Sell signal for every stock, and
 * notifies when a stock turns Buy, Buy more or Sell.
 */
public final class Engine {
    static final String FEED_URL = "__FEED_URL__";
    static final String PREFS = "portfolio";
    static final String CHANNEL = "signals";
    static final int JOB_ID = 1001;
    static final long HOUR = 60L * 60L * 1000L;

    private Engine() {}

    /** Hourly background refresh, kept across reboots. */
    static void schedule(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        for (JobInfo j : js.getAllPendingJobs()) {
            if (j.getId() == JOB_ID) return;
        }
        JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, RefreshJob.class))
                .setPeriodic(HOUR)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build();
        js.schedule(info);
    }

    static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36");
        c.setRequestProperty("Accept", "application/json,text/html,*/*");
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
        }
        c.disconnect();
        if (code >= 400) throw new Exception("HTTP " + code);
        return out.toString("UTF-8");
    }

    /** Live quote from Yahoo Finance's chart endpoint (no key needed). */
    static JSONObject quote(String symbol) throws Exception {
        String path = "/v8/finance/chart/" + URLEncoder.encode(symbol, "UTF-8") + "?interval=1d&range=1d";
        String body;
        try {
            body = get("https://query1.finance.yahoo.com" + path);
        } catch (Exception e) {
            body = get("https://query2.finance.yahoo.com" + path);
        }
        JSONObject meta = new JSONObject(body).getJSONObject("chart")
                .getJSONArray("result").getJSONObject(0).getJSONObject("meta");
        double price = meta.getDouble("regularMarketPrice");
        double prev = meta.optDouble("chartPreviousClose", meta.optDouble("previousClose", price));
        JSONObject q = new JSONObject();
        q.put("price", price);
        q.put("prevClose", prev);
        q.put("changePct", prev > 0 ? (price / prev - 1) * 100 : 0);
        q.put("high52", meta.optDouble("fiftyTwoWeekHigh", 0));
        q.put("low52", meta.optDouble("fiftyTwoWeekLow", 0));
        q.put("time", meta.optLong("regularMarketTime", 0) * 1000L);
        return q;
    }

    /** Backup source: the quote data Google Finance embeds in its quote page (about 1.2 MB a page). */
    static JSONObject quoteGoogle(String gsym) throws Exception {
        int c = gsym.indexOf(':');
        String sym = gsym.substring(0, c);
        String ex = gsym.substring(c + 1);
        String body = get("https://www.google.com/finance/quote/" + URLEncoder.encode(sym, "UTF-8") + ":" + ex + "?hl=en");
        Matcher m = googleMatch(body, sym, ex);
        if (m == null && sym.contains("&")) m = googleMatch(body, sym.replace("&", "\\u0026"), ex);
        if (m == null) throw new Exception("price not found");
        double price = Double.parseDouble(m.group(1));
        double prev = Double.parseDouble(m.group(4));
        double hi = 0;
        double lo = 0;
        Matcher h = Pattern.compile(">52-wk high</div><div class=\"[^\"]*\">[^0-9<]*([0-9,]+(?:\\.[0-9]+)?)").matcher(body);
        if (h.find()) hi = Double.parseDouble(h.group(1).replace(",", ""));
        Matcher l = Pattern.compile(">52-wk low</div><div class=\"[^\"]*\">[^0-9<]*([0-9,]+(?:\\.[0-9]+)?)").matcher(body);
        if (l.find()) lo = Double.parseDouble(l.group(1).replace(",", ""));
        JSONObject q = new JSONObject();
        q.put("price", price);
        q.put("prevClose", prev);
        q.put("changePct", prev > 0 ? (price / prev - 1) * 100 : 0);
        q.put("high52", hi);
        q.put("low52", lo);
        q.put("time", System.currentTimeMillis());
        q.put("src", "Google");
        return q;
    }

    static Matcher googleMatch(String body, String sym, String ex) {
        Matcher m = Pattern.compile("\\[\"" + Pattern.quote(sym) + "\",\"" + Pattern.quote(ex)
                + "\"\\],\"(?:[^\"\\\\]|\\\\.)*\",\\d+,(?:\"[A-Z]{3}\"|null),\\[(-?[0-9.Ee+-]+),(-?[0-9.Ee+-]+),(-?[0-9.Ee+-]+)[^\\]]*\\],null,(-?[0-9.Ee+-]+)")
                .matcher(body);
        return m.find() ? m : null;
    }

    /** Yahoo first (tiny responses); if Yahoo refuses, Google for the rest of this run. */
    static JSONObject quoteAny(String yahoo, String yahooAlt, String google, boolean[] yahooOk) throws Exception {
        if (yahooOk[0]) {
            try {
                JSONObject q = quote(yahoo);
                q.put("src", "Yahoo");
                return q;
            } catch (Exception e) {
                if (String.valueOf(e.getMessage()).contains("429")) yahooOk[0] = false;
                if (yahooAlt != null && !yahooAlt.isEmpty() && yahooOk[0]) {
                    try {
                        JSONObject q = quote(yahooAlt);
                        q.put("src", "Yahoo");
                        return q;
                    } catch (Exception ignored) {
                        // fall through to Google
                    }
                }
            }
        }
        return quoteGoogle(google);
    }

    /** Weekdays 9:00 am to 4:45 pm India time, when prices move. */
    static boolean marketHours() {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"));
        int dow = c.get(Calendar.DAY_OF_WEEK);
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return false;
        int mins = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        return mins >= 9 * 60 && mins <= 16 * 60 + 45;
    }

    static String today() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));
        return f.format(new Date());
    }

    static String rs(double v) {
        return "₹" + String.format(Locale.US, v >= 1000 ? "%,.0f" : "%,.1f", v);
    }

    static synchronized JSONObject run(Context ctx, boolean notify) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray errors = new JSONArray();

        JSONObject feed;
        try {
            feed = new JSONObject(get(FEED_URL + "?t=" + System.currentTimeMillis()));
            sp.edit().putString("feed", feed.toString()).apply();
        } catch (Exception e) {
            String cached = sp.getString("feed", null);
            if (cached == null) throw new Exception("Could not download the plan: " + e.getMessage());
            feed = new JSONObject(cached);
            errors.put("Plan not refreshed (" + e.getMessage() + "); using the saved copy");
        }

        JSONObject rules = feed.optJSONObject("rules");
        if (rules == null) rules = new JSONObject();
        double buyBand = rules.optDouble("buyBand", 0.02);
        double buyMoreDrop = rules.optDouble("buyMoreDrop", 0.07);
        double dayDrop = rules.optDouble("dayDrop", 0.07);
        double mktDay = rules.optDouble("marketDayDrop", 0.03);
        double mktHigh = rules.optDouble("marketFromHigh", 0.08);
        double maxWeight = rules.optDouble("maxWeight", 0.12);
        double capital = feed.optDouble("capital", 700000);
        String today = today();

        JSONObject prev = new JSONObject(sp.getString("signals", "{}"));
        boolean[] yahooOk = {true};
        double pfInvested = 0, pfValue = 0, pfPrevValue = 0;
        int pfOwned = 0, pfPriced = 0;
        JSONObject next = new JSONObject();
        JSONArray out = new JSONArray();
        JSONArray holdings = feed.optJSONArray("holdings");
        if (holdings == null) holdings = new JSONArray();

        for (int i = 0; i < holdings.length(); i++) {
            JSONObject h = new JSONObject(holdings.getJSONObject(i).toString());
            String name = h.optString("name");
            double entry = h.optDouble("entry", 0);
            double avg = h.isNull("avgCost") ? 0 : h.optDouble("avgCost", 0);
            int planned = h.optInt("plannedShares", 0);
            int bought = h.optInt("boughtShares", 0);
            String tag = h.isNull("analystTag") ? "" : h.optString("analystTag", "");
            boolean thesisOk = h.optBoolean("thesisOk", true);
            String nextBuyFrom = h.optString("nextBuyFrom", "");
            if (bought > 0) pfOwned++;

            JSONObject q = null;
            try {
                String sym = h.optString("symbol");
                q = quoteAny(h.optString("yahoo", sym + ".NS"), h.optString("yahooAlt", ""),
                        h.optString("google", sym + ":NSE"), yahooOk);
            } catch (Exception e) {
                errors.put(name + ": live price unavailable");
            }

            String signal;
            String reason;
            if (q == null) {
                signal = prev.optString(name, bought > 0 ? "Hold" : "Wait");
                reason = "No live price right now; showing the last signal";
            } else {
                double p = q.getDouble("price");
                double day = q.getDouble("changePct") / 100.0;
                h.put("price", p);
                h.put("prevClose", q.getDouble("prevClose"));
                if (bought > 0) {
                    pfValue += p * bought;
                    pfPrevValue += q.getDouble("prevClose") * bought;
                    pfInvested += (avg > 0 ? avg : entry) * bought;
                    pfPriced++;
                }
                h.put("changePct", q.getDouble("changePct"));
                h.put("high52", q.getDouble("high52"));
                h.put("quoteTime", q.getLong("time"));
                double weight = bought > 0 ? p * bought / capital : 0;
                h.put("value", p * bought);
                h.put("weight", weight * 100);
                if (bought > 0 && avg > 0) h.put("gainPct", (p / avg - 1) * 100);
                int firstLot = Math.max(1, Math.round(planned * 0.4f));
                int left = Math.max(0, planned - bought);
                int nextLot = Math.min(left, Math.max(1, Math.round(planned * 0.3f)));

                if ("Sell".equals(tag)) {
                    signal = "Sell";
                    reason = h.optString("note", "Latest review: thesis broken");
                } else if (bought == 0) {
                    if (!thesisOk || "Wait".equals(tag)) {
                        signal = "Wait";
                        reason = "Paused by the latest review";
                    } else if (entry > 0 && p <= entry * (1 + buyBand)) {
                        signal = "Buy";
                        reason = "Within " + Math.round(buyBand * 100) + "% of entry " + rs(entry)
                                + ". Buy about " + firstLot + " shares (first 40%), limit " + rs(entry);
                    } else {
                        signal = "Wait";
                        reason = String.format(Locale.US, "%.1f%% above entry ", (p / entry - 1) * 100) + rs(entry);
                    }
                } else {
                    if (weight > maxWeight) {
                        signal = "Sell";
                        reason = String.format(Locale.US, "Trim: %.1f%% of portfolio, above the %.0f%% cap", weight * 100, maxWeight * 100);
                    } else if (thesisOk && avg > 0 && p <= avg * (1 - buyMoreDrop)) {
                        signal = "Buy more";
                        reason = String.format(Locale.US, "%.1f%% below your cost ", (1 - p / avg) * 100) + rs(avg) + "; thesis intact";
                    } else if (thesisOk && day <= -dayDrop) {
                        signal = "Buy more";
                        reason = String.format(Locale.US, "Down %.1f%% today; thesis intact", -day * 100);
                    } else if (thesisOk && left > 0 && today.compareTo(nextBuyFrom) >= 0
                            && entry > 0 && p <= entry * (1 + buyBand)) {
                        signal = "Buy more";
                        reason = "Next tranche due: buy about " + nextLot + " more (" + bought + " of " + planned
                                + " owned), limit " + rs(entry);
                    } else if ("Buy more".equals(tag)) {
                        signal = "Buy more";
                        reason = h.optString("note", "Latest review says add");
                    } else {
                        signal = "Hold";
                        reason = left > 0 ? bought + " of " + planned + " shares owned; next lot from " + nextBuyFrom
                                : "Thesis intact";
                    }
                }
            }
            h.put("signal", signal);
            h.put("reason", reason);
            next.put(name, signal);

            String before = prev.optString(name, "");
            boolean actionable = signal.equals("Buy") || signal.equals("Buy more") || signal.equals("Sell");
            if (notify && actionable && !signal.equals(before)) {
                String priceText = q != null ? " Now " + rs(q.getDouble("price")) + "." : "";
                notifyUser(ctx, 100 + i, signal.toUpperCase(Locale.US) + ": " + name, reason + "." + priceText);
            }
            out.put(h);
        }

        JSONObject market = new JSONObject();
        try {
            JSONObject n = quoteAny("^NSEI", "", feed.optString("marketGoogle", "NIFTY_50:INDEXNSE"), yahooOk);
            double p = n.getDouble("price");
            double hi = n.getDouble("high52");
            double day = n.getDouble("changePct") / 100.0;
            boolean dip = day <= -mktDay || (hi > 0 && p <= hi * (1 - mktHigh));
            market = n;
            market.put("dip", dip);
            if (hi > 0) market.put("fromHighPct", (p / hi - 1) * 100);
            if (dip && notify && !today.equals(sp.getString("lastDip", ""))) {
                String text = String.format(Locale.US, "Nifty %,.0f (%+.1f%% today, %.1f%% below its 52-week high). ",
                        p, day * 100, hi > 0 ? (1 - p / hi) * 100 : 0)
                        + "Deploy the " + rs(feed.optDouble("reserve", 0)) + " reserve, value names first.";
                notifyUser(ctx, 99, "MARKET DIP: deploy reserve", text);
                sp.edit().putString("lastDip", today).apply();
            }
        } catch (Exception e) {
            errors.put("Nifty: live level unavailable");
        }

        JSONObject portfolio = new JSONObject();
        portfolio.put("owned", pfOwned);
        portfolio.put("priced", pfPriced);
        if (pfPriced > 0) {
            portfolio.put("value", pfValue);
            portfolio.put("invested", pfInvested);
            portfolio.put("dayChange", pfValue - pfPrevValue);
            portfolio.put("dayPct", pfPrevValue > 0 ? (pfValue / pfPrevValue - 1) * 100 : 0);
            portfolio.put("gain", pfValue - pfInvested);
            portfolio.put("gainPct", pfInvested > 0 ? (pfValue / pfInvested - 1) * 100 : 0);
        }

        JSONObject state = new JSONObject();
        state.put("portfolio", portfolio);
        state.put("fetchedAt", System.currentTimeMillis());
        state.put("planUpdated", feed.optString("updated", ""));
        state.put("summary", feed.optString("summary", ""));
        state.put("docUrl", feed.optString("docUrl", ""));
        state.put("reserve", feed.optDouble("reserve", 0));
        state.put("capital", capital);
        state.put("market", market);
        state.put("holdings", out);
        state.put("errors", errors);
        state.put("source", yahooOk[0] ? "Yahoo Finance" : "Google Finance");
        sp.edit().putString("signals", next.toString()).putString("state", state.toString()).apply();
        return state;
    }

    static void ensureChannel(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Buy and sell signals",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Alerts when a stock turns Buy, Buy more or Sell, and on market dips");
        nm.createNotificationChannel(ch);
    }

    static void notifyUser(Context ctx, int id, String title, String text) {
        ensureChannel(ctx);
        Intent i = new Intent(ctx, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, id, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        try {
            if (nm != null) nm.notify(id, n);
        } catch (SecurityException ignored) {
            // Notifications not allowed; the signal still shows in the app.
        }
    }
}
