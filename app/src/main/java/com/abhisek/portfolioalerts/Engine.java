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
 * For each market (India, US): downloads the plan from the GitHub repo, fetches live prices,
 * works out a Buy / Wait / Hold / Buy more / Sell signal for every stock, and notifies when a
 * stock turns Buy, Buy more or Sell, or when the market dips enough to deploy the reserve.
 */
public final class Engine {
    static final String FEED_URL = "__FEED_URL__";
    static final String FEED_URL_US = "__FEED_URL_US__";
    /** owner/repo whose "latest" release holds the app (replaced at build time). */
    static final String REPO = "__REPO__";
    static final int UPDATE_ID = 900;
    static final String[] MARKETS = {"india", "us"};
    static final String PREFS = "portfolio";
    static final String CHANNEL = "signals";
    static final int JOB_ID = 1001;
    static final long HOUR = 60L * 60L * 1000L;

    private Engine() {}

    static boolean isUs(String m) {
        return "us".equals(m);
    }

    static String feedUrl(String m) {
        return isUs(m) ? FEED_URL_US : FEED_URL;
    }

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

    /** Live quote from Yahoo Finance's chart endpoint (no key needed, tiny response). */
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
        q.put("src", "Yahoo");
        return q;
    }

    static Matcher googleMatch(String body, String sym, String ex) {
        Matcher m = Pattern.compile("\\[\"" + Pattern.quote(sym) + "\",\"" + Pattern.quote(ex)
                + "\"\\],\"(?:[^\"\\\\]|\\\\.)*\",\\d+,(?:\"[A-Z]{3}\"|null),\\[(-?[0-9.Ee+-]+),(-?[0-9.Ee+-]+),(-?[0-9.Ee+-]+)[^\\]]*\\],null,(-?[0-9.Ee+-]+)")
                .matcher(body);
        return m.find() ? m : null;
    }

    /** Backup source: the quote data Google Finance embeds in its quote page (about 1.2 MB a page). */
    static JSONObject quoteGoogle(String gsym) throws Exception {
        int c = gsym.lastIndexOf(':');
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

    /** Yahoo first; if Yahoo refuses (HTTP 429), Google for the rest of this run. */
    static JSONObject quoteAny(String yahoo, String yahooAlt, String google, boolean[] yahooOk) throws Exception {
        if (yahooOk[0]) {
            try {
                return quote(yahoo);
            } catch (Exception e) {
                if (String.valueOf(e.getMessage()).contains("429")) yahooOk[0] = false;
                if (yahooAlt != null && !yahooAlt.isEmpty() && yahooOk[0]) {
                    try {
                        return quote(yahooAlt);
                    } catch (Exception ignored) {
                        // fall through to Google
                    }
                }
            }
        }
        return quoteGoogle(google);
    }

    /** India: weekdays 9:00 am to 4:45 pm IST. US: weekdays 9:30 am to 4:15 pm New York time. */
    static boolean marketHours(String m) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone(isUs(m) ? "America/New_York" : "Asia/Kolkata"));
        int dow = c.get(Calendar.DAY_OF_WEEK);
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return false;
        int mins = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        return isUs(m) ? (mins >= 9 * 60 + 30 && mins <= 16 * 60 + 15) : (mins >= 9 * 60 && mins <= 16 * 60 + 45);
    }

    static boolean anyMarketOpen() {
        for (String m : MARKETS) {
            if (marketHours(m)) return true;
        }
        return false;
    }

    static String today(String m) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone(isUs(m) ? "America/New_York" : "Asia/Kolkata"));
        return f.format(new Date());
    }

    static String money(String cs, double v) {
        return cs + String.format(Locale.US, v >= 1000 ? "%,.0f" : "%,.2f", v);
    }

    static String shares(double s, boolean fractional) {
        return fractional ? String.format(Locale.US, "%.2f", s) : String.valueOf(Math.round(s));
    }

    /** Refreshes every market (or only those open now) and returns the combined state. */
    static synchronized JSONObject runAll(Context ctx, boolean notify, boolean onlyOpen) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        for (String m : MARKETS) {
            if (onlyOpen && !marketHours(m) && !planChanged(ctx, m)) continue;
            try {
                runMarket(ctx, m, notify);
            } catch (Exception e) {
                try {
                    JSONObject prev = new JSONObject(sp.getString("state_" + m, "{}"));
                    prev.put("error", String.valueOf(e.getMessage()));
                    sp.edit().putString("state_" + m, prev.toString()).apply();
                } catch (Exception ignored) {
                    // keep going with the other market
                }
            }
        }
        return combinedState(ctx);
    }

    static JSONObject combinedState(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONObject all = new JSONObject();
        for (String m : MARKETS) {
            try {
                all.put(m, new JSONObject(sp.getString("state_" + m, "{}")));
            } catch (Exception ignored) {
                // skip a broken saved state
            }
        }
        return all;
    }

    static void runMarket(Context ctx, String m, boolean notify) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean us = isUs(m);
        String label = us ? "US" : "India";
        JSONArray errors = new JSONArray();

        JSONObject feed;
        try {
            feed = new JSONObject(get(feedUrl(m) + "?t=" + System.currentTimeMillis()));
            String old = savedFeed(sp, m);
            if (notify && old != null && !old.equals(feed.toString())) {
                try {
                    String changes = planChanges(new JSONObject(old), feed);
                    if (!changes.isEmpty()) {
                        notifyUser(ctx, (us ? 300 : 100) - 2, label + " \u00B7 Plan updated", changes + ".");
                    }
                } catch (Exception ignored) {
                    // a malformed old copy: just take the new plan
                }
            }
            sp.edit().putString("feed_" + m, feed.toString()).apply();
        } catch (Exception e) {
            String cached = savedFeed(sp, m);
            if (cached == null) throw new Exception("Could not download the " + label + " plan: " + e.getMessage());
            feed = new JSONObject(cached);
            errors.put("Plan not refreshed (" + e.getMessage() + "); using the saved copy");
        }

        String cs = feed.optString("currencySymbol", us ? "$" : "\u20B9");
        boolean fractional = feed.optBoolean("fractional", us);
        JSONObject rules = feed.optJSONObject("rules");
        if (rules == null) rules = new JSONObject();
        double buyBand = rules.optDouble("buyBand", 0.02);
        double buyMoreDrop = rules.optDouble("buyMoreDrop", 0.07);
        double dayDrop = rules.optDouble("dayDrop", 0.07);
        double mktDay = rules.optDouble("marketDayDrop", 0.03);
        double mktHigh = rules.optDouble("marketFromHigh", us ? 0.12 : 0.08);
        double maxWeight = rules.optDouble("maxWeight", 0.12);
        double firstLotShare = rules.optDouble("firstLot", 0.4);
        double nextLotShare = rules.optDouble("nextLot", 0.3);
        String firstPct = Math.round(firstLotShare * 100) + "%";
        double capital = feed.optDouble("capital", us ? 10892 : 700000);
        String today = today(m);
        int idBase = us ? 300 : 100;

        String savedSignals = sp.getString("signals_" + m, us ? "{}" : sp.getString("signals", "{}"));
        JSONObject prev = new JSONObject(savedSignals);
        JSONObject next = new JSONObject();
        boolean[] yahooOk = {true};
        double pfInvested = 0;
        double pfValue = 0;
        double pfPrevValue = 0;
        int pfOwned = 0;
        int pfPriced = 0;
        JSONArray out = new JSONArray();
        JSONArray holdings = feed.optJSONArray("holdings");
        if (holdings == null) holdings = new JSONArray();

        for (int i = 0; i < holdings.length(); i++) {
            JSONObject h = new JSONObject(holdings.getJSONObject(i).toString());
            String name = h.optString("name");
            String sym = h.optString("symbol");
            double entry = h.optDouble("entry", 0);
            double avg = h.isNull("avgCost") ? 0 : h.optDouble("avgCost", 0);
            double planned = h.optDouble("plannedShares", 0);
            double bought = h.optDouble("boughtShares", 0);
            String tag = h.isNull("analystTag") ? "" : h.optString("analystTag", "");
            boolean thesisOk = h.optBoolean("thesisOk", true);
            String nextBuyFrom = h.optString("nextBuyFrom", "");
            boolean owned = bought > 1e-6;
            if (owned) pfOwned++;

            JSONObject q = null;
            try {
                q = quoteAny(h.optString("yahoo", us ? sym : sym + ".NS"), h.optString("yahooAlt", ""),
                        h.optString("google", us ? sym + ":NASDAQ" : sym + ":NSE"), yahooOk);
            } catch (Exception e) {
                errors.put(name + ": live price unavailable");
            }

            String signal;
            String reason;
            if (q == null) {
                signal = prev.optString(name, owned ? "Hold" : "Wait");
                reason = "No live price right now; showing the last signal";
            } else {
                double p = q.getDouble("price");
                double day = q.getDouble("changePct") / 100.0;
                h.put("price", p);
                h.put("prevClose", q.getDouble("prevClose"));
                h.put("changePct", q.getDouble("changePct"));
                h.put("high52", q.getDouble("high52"));
                h.put("quoteTime", q.getLong("time"));
                if (owned) {
                    pfValue += p * bought;
                    pfPrevValue += q.getDouble("prevClose") * bought;
                    pfInvested += (avg > 0 ? avg : entry) * bought;
                    pfPriced++;
                }
                double weight = owned ? p * bought / capital : 0;
                h.put("value", p * bought);
                h.put("weight", weight * 100);
                if (owned && avg > 0) h.put("gainPct", (p / avg - 1) * 100);
                double firstLot = planned * firstLotShare;
                double left = Math.max(0, planned - bought);
                double nextLot = Math.min(left, planned * nextLotShare);
                String firstText = fractional
                        ? "Buy about " + money(cs, firstLot * entry) + " (" + shares(firstLot, true) + " shares, first " + firstPct + ")"
                        : "Buy about " + Math.max(1, Math.round(firstLot)) + " shares (first " + firstPct + ")";
                String nextText = fractional
                        ? "buy about " + money(cs, nextLot * entry) + " more (" + shares(nextLot, true) + " shares)"
                        : "buy about " + Math.max(1, Math.round(nextLot)) + " more";

                if ("Sell".equals(tag)) {
                    signal = "Sell";
                    reason = h.optString("note", "Latest review: thesis broken");
                } else if (!owned) {
                    if (!thesisOk || "Wait".equals(tag)) {
                        signal = "Wait";
                        reason = "Paused by the latest review";
                    } else if (entry > 0 && p <= entry * (1 + buyBand)) {
                        signal = "Buy";
                        reason = "Within " + Math.round(buyBand * 100) + "% of entry " + money(cs, entry) + ". "
                                + firstText + ", limit " + money(cs, entry);
                    } else {
                        signal = "Wait";
                        reason = String.format(Locale.US, "%.1f%% above entry ", (p / entry - 1) * 100) + money(cs, entry);
                    }
                } else {
                    if (weight > maxWeight) {
                        signal = "Sell";
                        reason = String.format(Locale.US, "Trim: %.1f%% of portfolio, above the %.0f%% cap", weight * 100, maxWeight * 100);
                    } else if (thesisOk && avg > 0 && p <= avg * (1 - buyMoreDrop)) {
                        signal = "Buy more";
                        reason = String.format(Locale.US, "%.1f%% below your cost ", (1 - p / avg) * 100) + money(cs, avg) + "; thesis intact";
                    } else if (thesisOk && day <= -dayDrop) {
                        signal = "Buy more";
                        reason = String.format(Locale.US, "Down %.1f%% today; thesis intact", -day * 100);
                    } else if (thesisOk && left > 1e-6 && today.compareTo(nextBuyFrom) >= 0
                            && entry > 0 && p <= entry * (1 + buyBand)) {
                        signal = "Buy more";
                        reason = "Next tranche due: " + nextText + " (" + shares(bought, fractional) + " of "
                                + shares(planned, fractional) + " owned), limit " + money(cs, entry);
                    } else if ("Buy more".equals(tag)) {
                        signal = "Buy more";
                        reason = h.optString("note", "Latest review says add");
                    } else {
                        signal = "Hold";
                        reason = left > 1e-6
                                ? shares(bought, fractional) + " of " + shares(planned, fractional) + " shares owned"
                                        + (nextBuyFrom.isEmpty() ? "" : "; next lot from " + nextBuyFrom)
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
                String priceText = q != null ? " Now " + money(cs, q.getDouble("price")) + "." : "";
                notifyUser(ctx, idBase + i, label + " \u00B7 " + signal.toUpperCase(Locale.US) + ": " + name,
                        reason + "." + priceText);
            }
            out.put(h);
        }

        JSONObject mkCfg = feed.optJSONObject("market");
        if (mkCfg == null) mkCfg = new JSONObject();
        String mkName = mkCfg.optString("name", us ? "S&P 500" : "Nifty 50");
        JSONObject market = new JSONObject();
        try {
            JSONObject n = quoteAny(mkCfg.optString("yahoo", us ? "^GSPC" : "^NSEI"), "",
                    mkCfg.optString("google", feed.optString("marketGoogle", us ? ".INX:INDEXSP" : "NIFTY_50:INDEXNSE")), yahooOk);
            double p = n.getDouble("price");
            double hi = n.getDouble("high52");
            double day = n.getDouble("changePct") / 100.0;
            boolean dip = day <= -mktDay || (hi > 0 && p <= hi * (1 - mktHigh));
            market = n;
            market.put("dip", dip);
            if (hi > 0) market.put("fromHighPct", (p / hi - 1) * 100);
            if (dip && notify && !today.equals(sp.getString("lastDip_" + m, ""))) {
                String text = String.format(Locale.US, "%s %,.0f (%+.1f%% today, %.1f%% below its 52-week high). ",
                        mkName, p, day * 100, hi > 0 ? (1 - p / hi) * 100 : 0)
                        + "Deploy the " + money(cs, feed.optDouble("reserve", 0)) + " reserve, value names first.";
                notifyUser(ctx, idBase - 1, label + " \u00B7 MARKET DIP: deploy reserve", text);
                sp.edit().putString("lastDip_" + m, today).apply();
            }
        } catch (Exception e) {
            errors.put(mkName + ": live level unavailable");
        }
        market.put("name", mkName);

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
        state.put("id", m);
        state.put("label", label);
        state.put("currencySymbol", cs);
        state.put("fractional", fractional);
        state.put("fx", feed.optDouble("fx", 0));
        state.put("localCurrency", feed.optString("localCurrency", ""));
        state.put("fetchedAt", System.currentTimeMillis());
        state.put("planUpdated", feed.optString("updated", ""));
        state.put("summary", feed.optString("summary", ""));
        state.put("docUrl", feed.optString("docUrl", ""));
        state.put("reserve", feed.optDouble("reserve", 0));
        state.put("capital", capital);
        state.put("portfolio", portfolio);
        state.put("market", market);
        state.put("holdings", out);
        state.put("errors", errors);
        state.put("source", yahooOk[0] ? "Yahoo Finance" : "Google Finance");
        state.put("open", marketHours(m));
        sp.edit().putString("signals_" + m, next.toString()).putString("state_" + m, state.toString()).apply();
    }

    static String savedFeed(SharedPreferences sp, String m) {
        return sp.getString("feed_" + m, isUs(m) ? null : sp.getString("feed", null));
    }

    /** Cheap check (one small download) for a closed market: has the plan changed since the last run? */
    static boolean planChanged(Context ctx, String m) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            String now = new JSONObject(get(feedUrl(m) + "?t=" + System.currentTimeMillis())).toString();
            return !now.equals(savedFeed(sp, m));
        } catch (Exception e) {
            return false;
        }
    }

    static java.util.LinkedHashMap<String, JSONObject> bySymbol(JSONObject feed) {
        java.util.LinkedHashMap<String, JSONObject> map = new java.util.LinkedHashMap<>();
        JSONArray a = feed.optJSONArray("holdings");
        if (a == null) return map;
        for (int i = 0; i < a.length(); i++) {
            JSONObject h = a.optJSONObject(i);
            if (h != null) map.put(h.optString("symbol"), h);
        }
        return map;
    }

    static String tagOf(JSONObject h) {
        return h.isNull("analystTag") ? "" : h.optString("analystTag", "");
    }

    /** Plain-language list of what changed between two versions of a plan ("" if nothing that matters). */
    static String planChanges(JSONObject oldF, JSONObject newF) {
        String cs = newF.optString("currencySymbol", "");
        boolean fr = newF.optBoolean("fractional", false);
        java.util.LinkedHashMap<String, JSONObject> a = bySymbol(oldF), b = bySymbol(newF);
        java.util.List<String> parts = new java.util.ArrayList<>();
        java.util.List<String> added = new java.util.ArrayList<>(), removed = new java.util.ArrayList<>(),
                resized = new java.util.ArrayList<>();
        for (String k : b.keySet()) if (!a.containsKey(k)) added.add(b.get(k).optString("name", k));
        for (String k : a.keySet()) if (!b.containsKey(k)) removed.add(a.get(k).optString("name", k));
        for (String k : b.keySet()) {
            JSONObject o = a.get(k), n = b.get(k);
            if (o == null) continue;
            String name = n.optString("name", k);
            double ob = o.optDouble("boughtShares", 0), nb = n.optDouble("boughtShares", 0);
            if (nb > ob + 1e-6) {
                String avg = n.isNull("avgCost") ? "" : ", avg cost " + money(cs, n.optDouble("avgCost", 0));
                parts.add("Buy recorded: " + name + ", now " + shares(nb, fr) + " shares" + avg);
            } else if (nb < ob - 1e-6) {
                parts.add("Sale recorded: " + name + ", now " + shares(nb, fr) + " shares");
            }
            String ot = tagOf(o), nt = tagOf(n);
            if (!ot.equals(nt)) parts.add(nt.isEmpty() ? name + ": review flag cleared" : name + ": review says " + nt);
            if (Math.abs(o.optDouble("plannedShares", 0) - n.optDouble("plannedShares", 0)) > 1e-6) resized.add(name);
        }
        if (!added.isEmpty()) parts.add(0, "Added " + String.join(", ", added));
        if (!removed.isEmpty()) parts.add(added.isEmpty() ? 0 : 1, "Removed " + String.join(", ", removed));
        if (!resized.isEmpty()) parts.add("New target size: " + String.join(", ", resized));
        return String.join("; ", parts);
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
        Intent i = new Intent(ctx, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.putExtra("market", title.startsWith("US") ? "us" : "india");
        PendingIntent pi = PendingIntent.getActivity(ctx, id, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        notifyUser(ctx, id, title, text, pi);
    }

    static void notifyUser(Context ctx, int id, String title, String text, PendingIntent pi) {
        ensureChannel(ctx);
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
