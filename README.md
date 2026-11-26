# Portfolio Alerts

Android app for Abhisek's 10-year Indian growth portfolio. It checks live prices every hour and sends a notification when a stock turns **Buy**, **Buy more** or **Sell**, or when the market dips enough to deploy the reserve.

- **Download:** the latest APK is on the [Releases page](../../releases/latest) (`PortfolioAlerts.apk`).
- **Plan feed:** `data/portfolio.json` holds entry prices, your purchases (shares and average cost) and the latest review's calls. Claude's daily reviews update it; the app downloads it on every refresh.
- **Prices:** Yahoo Finance chart data (may be delayed by up to 15 minutes).
- **Build:** GitHub Actions builds and signs the APK on every push to `main` (`.github/workflows/build.yml`).

## How signals work

| Signal | When |
| --- | --- |
| Buy | Not bought yet, and the price is within 2% of the entry price |
| Wait | Not bought yet, and the price is more than 2% above entry (or the review paused it) |
| Hold | Bought, and nothing below applies |
| Buy more | Bought, and the price is 7%+ below your cost, or down 7%+ in a day, or the next tranche is due and the price is near entry, or the review says add |
| Sell | The review found the thesis broken, or a holding is above 12% of the portfolio (trim) |
| Market dip | Nifty 50 down 3%+ in a day, or 8%+ below its 52-week high |

Installing: open the APK on your phone and allow "Install unknown apps" for your browser or file manager when Android asks. Allow notifications when the app asks. For reliable hourly checks, set the app's battery usage to "Unrestricted" in Android settings.

Research, not financial advice.
