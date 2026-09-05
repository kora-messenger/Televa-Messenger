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

    /**
     * Creates the adaptive banner shown at the bottom of the main chat list.
     * The returned container has a solid background and is ready to be added
     * to the fragment content view with bottom gravity.
     */
    public static View createChatListBanner(Context context, int backgroundColor, Runnable onHeightChanged) {
        FrameLayout container = new FrameLayout(context);
        container.setBackgroundColor(backgroundColor);

        int widthPx = context.getResources().getDisplayMetrics().widthPixels;
        AdSize adSize = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthPx);

        AdView adView = new AdView(context);
        adView.setAdUnitId(CHAT_LIST_BANNER_UNIT_ID);
        adView.setAdSize(adSize);
        adView.setAdListener(new AdListener() {
            @Override
            public void onAdLoaded() {
                super.onAdLoaded();
                int newHeight = Math.max(adSize.getHeightInPixels(context), (int) AndroidUtilities.dp(50));
                boolean changed = !bannerVisible || bannerHeightPx != newHeight;
                bannerVisible = true;
                bannerHeightPx = newHeight + (int) AndroidUtilities.dp(6);
                container.setPadding(0, (int) AndroidUtilities.dp(3), 0, (int) AndroidUtilities.dp(3));
                container.setVisibility(View.VISIBLE);
                if (changed && onHeightChanged != null) {
                    onHeightChanged.run();
                }
            }

            @Override
            public void onAdFailedToLoad(LoadAdError error) {
                super.onAdFailedToLoad(error);
                bannerVisible = false;
                container.setVisibility(View.GONE);
                if (onHeightChanged != null) {
                    onHeightChanged.run();
                }
            }
        });
        container.addView(adView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL));

        container.setVisibility(View.GONE);
        AdRequest request = new AdRequest.Builder().build();
        adView.loadAd(request);
        return container;
    }
}
