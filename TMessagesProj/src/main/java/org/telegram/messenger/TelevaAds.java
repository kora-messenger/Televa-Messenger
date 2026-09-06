package org.telegram.messenger;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;

/**
 * Televa ads controller — AdMob banner integration for the Televa Messenger client.
 *
 * IDs are centralized here. To switch to production monetization, replace
 * ADMOB_APP_ID (also mirrored in AndroidManifest.xml meta-data) and
 * CHAT_LIST_BANNER_UNIT_ID with the values from the AdMob console
 * (console.admob.com → Apps → Televa → Ad units).
 *
 * Google's official sample/test configuration is used until production IDs
 * are configured, so the entire pipeline is live and functional end to end.
 */
public class TelevaAds {

    // AdMob app ID — must match the meta-data value in TMessagesProj/src/main/AndroidManifest.xml
    public static final String ADMOB_APP_ID = "ca-app-pub-7674351831585708~9900669574";

    // Ad unit shown on the main chat list
    public static final String CHAT_LIST_BANNER_UNIT_ID = "ca-app-pub-7674351831585708/3975118574";

    private static boolean initialized;
    private static volatile boolean bannerVisible;
    private static int bannerHeightPx;
    private static volatile boolean topBannerVisible;
    private static int topBannerHeightPx;

    public static synchronized void init(Context context) {
        if (initialized || context == null) {
            return;
        }
        initialized = true;
        try {
            MobileAds.initialize(context, initializationStatus -> {
            });
        } catch (Throwable ignore) {
        }
    }

    public static boolean isBannerVisible() {
        return bannerVisible;
    }

    public static int getBannerHeightPx() {
        return bannerVisible ? bannerHeightPx : 0;
    }

    public static boolean isTopBannerVisible() {
        return topBannerVisible;
    }

    public static int getTopBannerHeightPx() {
        return topBannerVisible ? topBannerHeightPx : 0;
    }

    /**
     * Creates the adaptive banner shown at the bottom of the main chat list.
     * The returned container has a solid background and is ready to be added
     * to the fragment content view with bottom gravity.
     */
    public static View createChatListBanner(Context context, int backgroundColor, Runnable onHeightChanged) {
        return createBanner(context, backgroundColor, onHeightChanged, false);
    }

    /**
     * Creates the adaptive banner pinned at the top of the main chat list,
     * below the search bar / folder tabs. Uses its own ad request and height
     * tracking so the top and bottom banners load independently.
     */
    public static View createTopChatListBanner(Context context, int backgroundColor, Runnable onHeightChanged) {
        return createBanner(context, backgroundColor, onHeightChanged, true);
    }

    private static View createBanner(Context context, int backgroundColor, Runnable onHeightChanged, boolean top) {
        FrameLayout container = new FrameLayout(context);
        container.setBackgroundColor(backgroundColor);

        int widthPx = context.getResources().getDisplayMetrics().widthPixels;
        AdSize adSize = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthPx);

        AdView adView = new AdView(context);
        adView.setAdUnitId(CHAT_LIST_BANNER_UNIT_ID);
        adView.setAdSize(adSize);

        // Televa: a single ad request at startup is not enough. Brand-new ad
        // units frequently get no fill on the first requests, which used to
        // leave the banner permanently hidden until an app restart. Retry
        // with a growing backoff and log every failure so the exact AdMob
        // error code is visible in the logs.
        final int[] attempt = {0};
        final Runnable[] loadRequest = new Runnable[1];
        loadRequest[0] = () -> {
            if (!container.isAttachedToWindow()) {
                return; // screen gone; no point loading into a dead view
            }
            try {
                adView.loadAd(new AdRequest.Builder().build());
            } catch (Throwable e) {
                FileLog.e("TelevaAds: banner load threw", e);
            }
        };

        adView.setAdListener(new AdListener() {
            @Override
            public void onAdLoaded() {
                super.onAdLoaded();
                attempt[0] = 0;
                int newHeight = Math.max(adSize.getHeightInPixels(context), (int) AndroidUtilities.dp(50));
                boolean changed;
                if (top) {
                    changed = !topBannerVisible || topBannerHeightPx != newHeight;
                    topBannerVisible = true;
                    topBannerHeightPx = newHeight + (int) AndroidUtilities.dp(6);
                } else {
                    changed = !bannerVisible || bannerHeightPx != newHeight;
                    bannerVisible = true;
                    bannerHeightPx = newHeight + (int) AndroidUtilities.dp(6);
                }
                container.setPadding(0, (int) AndroidUtilities.dp(3), 0, (int) AndroidUtilities.dp(3));
                container.setVisibility(View.VISIBLE);
                if (changed && onHeightChanged != null) {
                    onHeightChanged.run();
                }
            }

            @Override
            public void onAdFailedToLoad(LoadAdError error) {
                super.onAdFailedToLoad(error);
                if (top) {
                    topBannerVisible = false;
                } else {
                    bannerVisible = false;
                }
                container.setVisibility(View.GONE);
                if (onHeightChanged != null) {
                    onHeightChanged.run();
                }
                if (error != null) {
                    FileLog.e("TelevaAds: banner (" + (top ? "top" : "bottom") + ") failed, code=" + error.getCode() + " msg=" + error.getMessage() + " attempt=" + attempt[0]);
                }
                // Backoff: 15s, 30s, 60s, 120s, then 240s for later attempts.
                // AdMob units can take hours to warm up, so keep retrying
                // while the screen is alive.
                attempt[0]++;
                long delayMs;
                if (attempt[0] <= 4) {
                    delayMs = 15_000L << (attempt[0] - 1);
                } else {
                    delayMs = 240_000L;
                }
                AndroidUtilities.runOnUIThread(loadRequest[0], delayMs);
            }
        });
        container.addView(adView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL));

        container.setVisibility(View.GONE);
        adView.loadAd(new AdRequest.Builder().build());
        return container;
    }
}
