package com.midori.playmask;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "PlayMask";
    private static final String TARGET_PACKAGE = "com.android.vending";
    private static final String PREF_NAME = "play_email_mask_prefs";
    private static final String KEY_MASK_ENABLED = "mask_emails_enabled";

    // Known and dynamically discovered OneGoogle Resource IDs in com.android.vending
    private static final Set<Integer> sChevronImageIds = Collections.synchronizedSet(new HashSet<Integer>());
    private static final Set<Integer> sChevronContainerIds = Collections.synchronizedSet(new HashSet<Integer>());
    private static final Set<Integer> sAccountEmailIds = Collections.synchronizedSet(new HashSet<Integer>());
    private static final Set<Integer> sAccountNameIds = Collections.synchronizedSet(new HashSet<Integer>());
    private static final Set<Integer> sHeaderAvatarsIds = Collections.synchronizedSet(new HashSet<Integer>());

    static {
        // Pre-seed verified fallback IDs for known Google Play Store versions:
        // Google Play v53.4+ (Oct 2026)
        sChevronImageIds.add(0x7f0b09a2);           // res/LHQ.xml & res/2PP.xml (account expand chevron arrow)
        sChevronContainerIds.add(0x7f0b09e4);       // res/2PP.xml FrameLayout container
        sAccountEmailIds.add(0x7f0b09c9);           // res/2PP.xml & res/ty4.xml
        sAccountEmailIds.add(0x7f0b09aa);           // res/nSe.xml
        sAccountNameIds.add(0x7f0b09c7);            // res/2PP.xml & res/ty4.xml
        sAccountNameIds.add(0x7f0b09a9);            // res/nSe.xml
        sHeaderAvatarsIds.add(0x7f0b0673);          // res/LHQ.xml HeaderAvatarsRecyclerView

        // Google Play v53.3+ (Sep 2026)
        sChevronImageIds.add(0x7f0b09a3);           // res/LHQ.xml & res/2PP.xml (account expand chevron arrow)
        sChevronContainerIds.add(0x7f0b09e5);       // res/2PP.xml FrameLayout container
        sAccountEmailIds.add(0x7f0b09ca);           // res/2PP.xml & res/ty4.xml
        sAccountEmailIds.add(0x7f0b09ab);           // res/nSe.xml
        sAccountNameIds.add(0x7f0b09c8);            // res/2PP.xml & res/ty4.xml
        sAccountNameIds.add(0x7f0b09aa);            // res/nSe.xml
        sHeaderAvatarsIds.add(0x7f0b0674);          // res/LHQ.xml HeaderAvatarsRecyclerView

        // Google Play v50.7 ~ v53.2 (Previous versions)
        sChevronImageIds.add(0x7f0b09a1);
        sChevronContainerIds.add(0x7f0b09e3);
        sAccountEmailIds.add(0x7f0b09c8);
        sAccountEmailIds.add(0x7f0b09a9);
        sAccountNameIds.add(0x7f0b09c6);
        sAccountNameIds.add(0x7f0b09a8);
        sHeaderAvatarsIds.add(0x7f0b0672);
    }

    // Tag and extra field keys (String-based, zero resource-id conflicts)
    private static final String TAG_TOGGLE_BUTTON = "playmask_toggle_btn";
    private static final String EXTRA_FIELD_ORIG_EMAIL = "playmask_orig_email";

    // Track active email TextViews and original email texts
    private static final Map<TextView, String> sOriginalEmails =
        Collections.synchronizedMap(new WeakHashMap<TextView, String>());

    // Track active toggle ImageView instances across screens/dialogs
    private static final Set<ImageView> sTrackedToggleIcons =
        Collections.newSetFromMap(new WeakHashMap<ImageView, Boolean>());

    // State
    private static volatile boolean sIsMasked = true;
    private static volatile boolean sPrefLoaded = false;
    private static volatile boolean sDiscovered = false;

    // ThreadLocal guard to prevent recursion during batch UI text updates
    private static final ThreadLocal<Boolean> sIsUpdating = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        if (!TARGET_PACKAGE.equals(lpparam.processName)) {
            return;
        }

        XposedBridge.log(TAG + ": Initializing in " + lpparam.packageName);

        if (lpparam.appInfo != null && lpparam.appInfo.sourceDir != null) {
            discoverResourceIds(lpparam.appInfo.sourceDir);
            sDiscovered = true;
        }

        hookLayoutInflater(lpparam.classLoader);
        hookSelectedAccountView(lpparam.classLoader);
        hookTextView(lpparam.classLoader);
    }

    private static void ensureDiscovery(Context context) {
        if (sDiscovered || context == null) return;
        synchronized (MainHook.class) {
            if (sDiscovered) return;
            try {
                String codePath = context.getPackageCodePath();
                if (codePath != null) {
                    discoverResourceIds(codePath);
                    sDiscovered = true;
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": Note: Context resource auto-discovery skipped (" + t.getMessage() + ")");
            }
        }
    }

    private static byte[] readZipEntry(ZipFile zf, ZipEntry entry) {
        if (entry == null) return null;
        try (InputStream is = zf.getInputStream(entry); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int r;
            while ((r = is.read(buf)) != -1) bos.write(buf, 0, r);
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean containsBytes(byte[] data, byte[] target) {
        if (data == null || target == null || data.length < target.length) return false;
        outer:
        for (int i = 0; i <= data.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (data[i + j] != target[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    /**
     * Dynamically inspects the target Google Play APK at startup to parse compiled AXML layouts
     * (res/LHQ.xml, res/2PP.xml, res/nSe.xml) and extract the exact chevron, container, email, and name IDs.
     * Includes fingerprint-based discovery fallback in case future Google Play versions rename the layouts.
     * Guarantees 100% automatic forward compatibility with future Google Play Store updates.
     */
    private static void discoverResourceIds(String apkPath) {
        if (apkPath == null) return;
        try (ZipFile zf = new ZipFile(apkPath)) {
            String lhqName = "res/LHQ.xml";
            String legacyName = "res/2PP.xml";
            String bentoTextName = "res/nSe.xml";

            ZipEntry lhqEntry = zf.getEntry(lhqName);
            ZipEntry legacyEntry = zf.getEntry(legacyName);
            ZipEntry bentoTextEntry = zf.getEntry(bentoTextName);

            // Robust fingerprint discovery fallback: if Google renames layout XMLs in a future release
            if (lhqEntry == null || legacyEntry == null) {
                byte[] bentoFp = "HeaderAvatarsRecyclerView".getBytes(StandardCharsets.UTF_8);
                byte[] discFp = "AccountParticleDisc".getBytes(StandardCharsets.UTF_8);
                byte[] frameFp = "FrameLayout".getBytes(StandardCharsets.UTF_8);

                Enumeration<? extends ZipEntry> entries = zf.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry ze = entries.nextElement();
                    String name = ze.getName();
                    if (name.startsWith("res/") && name.endsWith(".xml") && name.lastIndexOf('/') == 3) {
                        byte[] data = readZipEntry(zf, ze);
                        if (data != null) {
                            if (lhqEntry == null && containsBytes(data, bentoFp)) {
                                lhqEntry = ze;
                                lhqName = name;
                            } else if (legacyEntry == null && containsBytes(data, discFp) && containsBytes(data, frameFp)) {
                                legacyEntry = ze;
                                legacyName = name;
                            }
                        }
                    }
                    if (lhqEntry != null && legacyEntry != null) break;
                }
            }

            Set<Integer> discoveredChevronImages = new HashSet<>();
            Set<Integer> discoveredChevronContainers = new HashSet<>();
            Set<Integer> discoveredAccountEmails = new HashSet<>();
            Set<Integer> discoveredAccountNames = new HashSet<>();
            Set<Integer> discoveredHeaderAvatars = new HashSet<>();

            if (lhqEntry != null) {
                parseAxmlAndExtractIds(lhqName, readZipEntry(zf, lhqEntry),
                        discoveredChevronImages, discoveredChevronContainers,
                        discoveredAccountEmails, discoveredAccountNames, discoveredHeaderAvatars);
            }
            if (legacyEntry != null) {
                parseAxmlAndExtractIds(legacyName, readZipEntry(zf, legacyEntry),
                        discoveredChevronImages, discoveredChevronContainers,
                        discoveredAccountEmails, discoveredAccountNames, discoveredHeaderAvatars);
            }
            if (bentoTextEntry != null) {
                parseAxmlAndExtractIds(bentoTextName, readZipEntry(zf, bentoTextEntry),
                        discoveredChevronImages, discoveredChevronContainers,
                        discoveredAccountEmails, discoveredAccountNames, discoveredHeaderAvatars);
            }

            // If discovery succeeded in extracting IDs from the current APK,
            // promote discovered IDs as the single source of truth to purge stale cross-version fallbacks.
            if (!discoveredChevronImages.isEmpty()) {
                sChevronImageIds.clear();
                sChevronImageIds.addAll(discoveredChevronImages);
            }
            if (!discoveredChevronContainers.isEmpty()) {
                sChevronContainerIds.clear();
                sChevronContainerIds.addAll(discoveredChevronContainers);
            }
            if (!discoveredAccountEmails.isEmpty()) {
                sAccountEmailIds.clear();
                sAccountEmailIds.addAll(discoveredAccountEmails);
            }
            if (!discoveredAccountNames.isEmpty()) {
                sAccountNameIds.clear();
                sAccountNameIds.addAll(discoveredAccountNames);
            }
            if (!discoveredHeaderAvatars.isEmpty()) {
                sHeaderAvatarsIds.clear();
                sHeaderAvatarsIds.addAll(discoveredHeaderAvatars);
            }

            XposedBridge.log(TAG + ": Dynamic resource discovery complete. Chevron IDs=" + sChevronImageIds + ", Container IDs=" + sChevronContainerIds);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Note: APK resource auto-discovery skipped (" + t.getMessage() + "), using fallback ID sets");
        }
    }

    private static void parseAxmlAndExtractIds(String xmlName, byte[] data,
                                               Set<Integer> outChevronImages,
                                               Set<Integer> outChevronContainers,
                                               Set<Integer> outAccountEmails,
                                               Set<Integer> outAccountNames,
                                               Set<Integer> outHeaderAvatars) {
        if (data == null || data.length < 8) return;
        try {
            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int magic = buf.getInt(0);
            if (magic != 0x00080003) return;

            List<String> stringPool = new ArrayList<>();
            int pos = 8;
            while (pos < data.length - 8) {
                int chunkType = buf.getInt(pos);
                int chunkSize = buf.getInt(pos + 4);
                if (chunkSize <= 0) break;

                if (chunkType == 0x001c0001) { // RES_STRING_POOL_TYPE
                    int stringCount = buf.getInt(pos + 8);
                    int flags = buf.getInt(pos + 16);
                    boolean isUtf8 = (flags & (1 << 8)) != 0;
                    int stringsStart = pos + buf.getInt(pos + 20);
                    int[] offsets = new int[stringCount];
                    for (int i = 0; i < stringCount; i++) {
                        offsets[i] = buf.getInt(pos + 28 + i * 4);
                    }
                    for (int i = 0; i < stringCount; i++) {
                        int sp = stringsStart + offsets[i];
                        if (isUtf8) {
                            while (sp < data.length && (data[sp] & 0x80) != 0) sp++;
                            sp++;
                            int blen = 0;
                            int shift = 0;
                            while (sp < data.length) {
                                byte b = data[sp++];
                                blen |= (b & 0x7f) << shift;
                                shift += 7;
                                if ((b & 0x80) == 0) break;
                            }
                            if (sp + blen <= data.length) {
                                stringPool.add(new String(data, sp, blen, StandardCharsets.UTF_8));
                            } else {
                                stringPool.add("");
                            }
                        } else {
                            if (sp + 2 <= data.length) {
                                int ulen = buf.getShort(sp) & 0xffff;
                                sp += 2;
                                if (sp + ulen * 2 <= data.length) {
                                    stringPool.add(new String(data, sp, ulen * 2, StandardCharsets.UTF_16LE));
                                } else {
                                    stringPool.add("");
                                }
                            } else {
                                stringPool.add("");
                            }
                        }
                    }
                } else if (chunkType == 0x00100102) { // RES_XML_START_ELEMENT_TYPE
                    int nameIdx = buf.getInt(pos + 20);
                    String elemName = (nameIdx >= 0 && nameIdx < stringPool.size()) ? stringPool.get(nameIdx) : "";
                    int attrStart = buf.getShort(pos + 24) & 0xffff;
                    int attrSize = buf.getShort(pos + 26) & 0xffff;
                    int attrCount = buf.getShort(pos + 28) & 0xffff;

                    Map<String, Integer> attrs = new HashMap<>();
                    for (int i = 0; i < attrCount; i++) {
                        int ap = pos + 16 + attrStart + i * attrSize;
                        if (ap + 20 <= data.length) {
                            int attrNameIdx = buf.getInt(ap + 4);
                            int attrData = buf.getInt(ap + 16);
                            String attrName = (attrNameIdx >= 0 && attrNameIdx < stringPool.size()) ? stringPool.get(attrNameIdx) : String.valueOf(attrNameIdx);
                            attrs.put(attrName, attrData);
                        }
                    }

                    if (attrs.containsKey("id")) {
                        int idVal = attrs.get("id");
                        // Bento layout detection
                        if (elemName.contains("HeaderAvatarsRecyclerView")) {
                            outHeaderAvatars.add(idVal);
                        } else if (elemName.contains("ImageView") && attrs.containsKey("layout_constraintEnd_toEndOf") && attrs.containsKey("tint")) {
                            outChevronImages.add(idVal);
                        } else if ("FrameLayout".equals(elemName) && attrs.containsKey("layout_constraintEnd_toEndOf")
                                && attrs.containsKey("layout_constraintTop_toTopOf") && attrs.containsKey("layout_constraintBottom_toBottomOf")) {
                            outChevronContainers.add(idVal);
                        } else if (elemName.contains("ImageView") && attrs.containsKey("srcCompat")) {
                            outChevronImages.add(idVal);
                        } else if (elemName.contains("TextView")) {
                            if (attrs.containsKey("layout_constraintTop_toBottomOf")) {
                                outAccountEmails.add(idVal);
                            } else if (attrs.containsKey("layout_constraintVertical_chainStyle")
                                    || (attrs.containsKey("layout_constraintTop_toTopOf") && attrs.containsKey("layout_constraintBottom_toTopOf"))) {
                                outAccountNames.add(idVal);
                            }
                        }
                    }
                }
                pos += chunkSize;
            }
        } catch (Throwable ignored) {}
    }

    private static boolean isAccountView(TextView tv) {
        if (tv == null) return false;
        ensureDiscovery(tv.getContext());
        int id = tv.getId();
        if (sAccountEmailIds.contains(id) || sAccountNameIds.contains(id)) {
            return true;
        }
        // Inspect ancestor classes in view hierarchy
        ViewParent p = tv.getParent();
        for (int i = 0; i < 5 && p != null; i++) {
            String name = p.getClass().getName();
            if (name.contains("SelectedAccountView") ||
                name.contains("AccountParticle") ||
                name.contains("bento") ||
                name.contains("onegoogle")) {
                return true;
            }
            if (p instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) p;
                for (int emailId : sAccountEmailIds) {
                    if (vg.findViewById(emailId) != null) return true;
                }
                for (int chevronId : sChevronImageIds) {
                    if (vg.findViewById(chevronId) != null) return true;
                }
                for (int containerId : sChevronContainerIds) {
                    if (vg.findViewById(containerId) != null) return true;
                }
            }
            p = p.getParent();
        }
        return false;
    }

    /**
     * Strictly verifies that the target container is the active account card container.
     * Prevents false-positive injection into dialog toolbars, close buttons, or non-account headers.
     */
    private static boolean isAccountContainer(ViewGroup vg) {
        if (vg == null) return false;
        String name = vg.getClass().getName();
        if (name.contains("SelectedAccountView") || name.contains("ConstraintLayoutInterceptingTouches")) {
            return true;
        }
        // Verify container contains account email, account name, or avatar fast-switcher
        for (int id : sAccountEmailIds) {
            if (vg.findViewById(id) != null) return true;
        }
        for (int id : sAccountNameIds) {
            if (vg.findViewById(id) != null) return true;
        }
        for (int id : sHeaderAvatarsIds) {
            if (vg.findViewById(id) != null) return true;
        }
        // Check for AccountParticleDisc or HeaderAvatarsRecyclerView in direct/indirect children
        for (int i = 0; i < vg.getChildCount(); i++) {
            View child = vg.getChildAt(i);
            if (child != null) {
                String cName = child.getClass().getName();
                if (cName.contains("AccountParticleDisc") ||
                    cName.contains("AvatarView") ||
                    cName.contains("HeaderAvatarsRecyclerView")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int dpToPx(Context context, float dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    private static boolean getMaskPreference(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            return prefs.getBoolean(KEY_MASK_ENABLED, true);
        } catch (Throwable t) {
            return true;
        }
    }

    private static void saveMaskPreference(Context context, boolean masked) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(KEY_MASK_ENABLED, masked).apply();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Error saving preference: " + t);
        }
    }

    private static boolean isConstraintLayout(ViewGroup vg) {
        if (vg == null) return false;
        Class<?> cls = vg.getClass();
        while (cls != null && cls != ViewGroup.class && cls != Object.class) {
            if (cls.getName().contains("ConstraintLayout")) return true;
            cls = cls.getSuperclass();
        }
        try {
            Object lp = XposedHelpers.callMethod(vg, "generateDefaultLayoutParams");
            if (lp != null) {
                String lpName = lp.getClass().getName();
                if (lpName.contains("ConstraintLayout") || lpName.equals("defpackage.lgt")) {
                    return true;
                }
                for (Field f : lp.getClass().getFields()) {
                    if ("u".equals(f.getName()) || "endToStart".equals(f.getName())) {
                        return true;
                    }
                }
                for (Field f : lp.getClass().getDeclaredFields()) {
                    if ("u".equals(f.getName()) || "endToStart".equals(f.getName())) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean isInsideAccountList(View v) {
        if (v == null) return false;
        ViewParent p = v.getParent();
        while (p != null) {
            String name = p.getClass().getName();
            // HeaderAvatarsRecyclerView in Bento header is NOT the expanded account list
            if (name.contains("HeaderAvatarsRecyclerView")) {
                p = p.getParent();
                continue;
            }
            if (p instanceof View) {
                int id = ((View) p).getId();
                if (sHeaderAvatarsIds.contains(id)) {
                    p = p.getParent();
                    continue;
                }
            }
            if (name.contains("RecyclerView") || name.contains("ListView")) {
                return true;
            }
            p = p.getParent();
        }
        return false;
    }

    private static void trackAndMaskEmailsInView(View v) {
        if (v == null) return;
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            if (isAccountView(tv)) {
                CharSequence cs = tv.getText();
                if (cs != null) {
                    String textStr = cs.toString();
                    String extraOrig = (String) XposedHelpers.getAdditionalInstanceField(tv, EXTRA_FIELD_ORIG_EMAIL);
                    String realEmail = (extraOrig != null) ? extraOrig : null;
                    if (realEmail == null && EmailMaskUtils.containsEmail(textStr) && !textStr.contains("***")) {
                        realEmail = textStr;
                        XposedHelpers.setAdditionalInstanceField(tv, EXTRA_FIELD_ORIG_EMAIL, realEmail);
                    }
                    if (realEmail != null || EmailMaskUtils.containsEmail(textStr)) {
                        if (realEmail == null) {
                            realEmail = textStr;
                        }
                        sOriginalEmails.put(tv, realEmail);
                        if (sIsMasked) {
                            sIsUpdating.set(Boolean.TRUE);
                            try {
                                tv.setText(EmailMaskUtils.maskEmail(realEmail));
                            } finally {
                                sIsUpdating.set(Boolean.FALSE);
                            }
                        } else {
                            sIsUpdating.set(Boolean.TRUE);
                            try {
                                tv.setText(realEmail);
                            } finally {
                                sIsUpdating.set(Boolean.FALSE);
                            }
                        }
                    }
                }
            }
        } else if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                trackAndMaskEmailsInView(vg.getChildAt(i));
            }
        }
    }

    /**
     * Toggles mask state in real-time, instantly updating all visible email texts
     * and toggle icon drawables across the current screen without restarting the app.
     */
    private static void toggleMaskState(Context context) {
        sIsMasked = !sIsMasked;
        saveMaskPreference(context, sIsMasked);

        // 1. Update all tracked toggle icon Drawables
        synchronized (sTrackedToggleIcons) {
            for (ImageView iv : sTrackedToggleIcons) {
                if (iv != null && iv.getDrawable() instanceof MaskDrawable) {
                    ((MaskDrawable) iv.getDrawable()).setMasked(sIsMasked);
                    iv.invalidate();
                }
            }
        }

        // 2. Discover and update any newly attached email TextViews across tracked hierarchies
        synchronized (sTrackedToggleIcons) {
            for (ImageView iv : sTrackedToggleIcons) {
                if (iv != null) {
                    View root = iv.getRootView();
                    if (root != null) {
                        trackAndMaskEmailsInView(root);
                    }
                }
            }
        }

        // 3. Update all known active email TextViews
        sIsUpdating.set(Boolean.TRUE);
        try {
            synchronized (sOriginalEmails) {
                for (Map.Entry<TextView, String> entry : sOriginalEmails.entrySet()) {
                    TextView tv = entry.getKey();
                    String orig = entry.getValue();
                    if (tv != null && orig != null) {
                        tv.setText(sIsMasked ? EmailMaskUtils.maskEmail(orig) : orig);
                    }
                }
            }
        } finally {
            sIsUpdating.set(Boolean.FALSE);
        }
    }

    private static void setField(Object obj, String fieldName, int val) {
        if (obj == null) return;
        try {
            Field f = obj.getClass().getField(fieldName);
            f.setAccessible(true);
            f.setInt(obj, val);
            return;
        } catch (Throwable ignored) {}
        try {
            Field f = obj.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.setInt(obj, val);
        } catch (Throwable ignored) {}
    }

    private static int getField(Object obj, String fieldName, int defaultVal) {
        if (obj == null) return defaultVal;
        try {
            Field f = obj.getClass().getField(fieldName);
            f.setAccessible(true);
            return f.getInt(obj);
        } catch (Throwable ignored) {}
        try {
            Field f = obj.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.getInt(obj);
        } catch (Throwable ignored) {}
        return defaultVal;
    }

    private static ColorStateList resolveIconTint(Context context, View chevronView) {
        // 1. Inherit from existing chevron ImageView if present
        if (chevronView instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) chevronView;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                if (child instanceof ImageView) {
                    ColorStateList tint = ((ImageView) child).getImageTintList();
                    if (tint != null) {
                        return tint;
                    }
                }
            }
        } else if (chevronView instanceof ImageView) {
            ColorStateList tint = ((ImageView) chevronView).getImageTintList();
            if (tint != null) {
                return tint;
            }
        }

        // 2. Resolve theme colorControlNormal or colorOnSurfaceVariant
        TypedValue tv = new TypedValue();
        int[] attrs = new int[] {
            android.R.attr.colorControlNormal,
            context.getResources().getIdentifier("colorOnSurfaceVariant", "attr", context.getPackageName())
        };
        for (int attr : attrs) {
            if (attr != 0 && context.getTheme().resolveAttribute(attr, tv, true)) {
                if (tv.resourceId != 0) {
                    try {
                        return context.getColorStateList(tv.resourceId);
                    } catch (Throwable ignored) {}
                }
                if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    return ColorStateList.valueOf(tv.data);
                }
            }
        }

        // 3. Fallback: Google Material 3 onSurfaceVariant (#C4C7C5 in dark, #444746 in light)
        boolean isDark = (context.getResources().getConfiguration().uiMode &
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        return ColorStateList.valueOf(isDark ? 0xFFC4C7C5 : 0xFF444746);
    }

    private static boolean isChevronImageView(View v) {
        if (v == null || !(v instanceof ImageView)) {
            return false;
        }
        if (isCloseOrDismissButton(v)) {
            return false;
        }
        if (TAG_TOGGLE_BUTTON.equals(v.getTag())) {
            return false;
        }
        return true;
    }

    /**
     * Resolves the Chevron ImageView strictly via verified chevron IDs.
     * Rejects dialog toolbars, close buttons ("X"), and non-chevron views.
     */
    private static View findChevronView(View root) {
        if (root == null) return null;

        // 1. Search directly by known/discovered chevron image ID
        for (int id : sChevronImageIds) {
            if (root.getId() == id && isChevronImageView(root)) {
                return root;
            }
            View v = root.findViewById(id);
            if (v != null) {
                if (isChevronImageView(v)) {
                    return v;
                }
                if (v instanceof ViewGroup) {
                    ViewGroup vg = (ViewGroup) v;
                    for (int i = 0; i < vg.getChildCount(); i++) {
                        View child = vg.getChildAt(i);
                        if (isChevronImageView(child)) {
                            return child;
                        }
                    }
                }
            }
        }

        // 2. Search by known/discovered chevron container ID
        for (int containerId : sChevronContainerIds) {
            View container = (root.getId() == containerId) ? root : root.findViewById(containerId);
            if (container instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) container;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    View child = vg.getChildAt(i);
                    if (isChevronImageView(child)) {
                        return child;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Scoped structural fallback: Searches ONLY inside a verified account card container
     * for an end-anchored ImageView. Physically cannot match dialog top-bars or close buttons.
     */
    private static View findChevronInsideAccountContainer(ViewGroup accountContainer) {
        if (accountContainer == null || !isAccountContainer(accountContainer)) return null;
        for (int i = 0; i < accountContainer.getChildCount(); i++) {
            View child = accountContainer.getChildAt(i);
            if (child == null || isCloseOrDismissButton(child)) continue;
            if (TAG_TOGGLE_BUTTON.equals(child.getTag())) continue;

            ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (lp == null) continue;

            int endToEnd = getField(lp, "endToEnd", getField(lp, "v", -1));
            int rightToRight = getField(lp, "rightToRight", getField(lp, "ao", -1));
            if (endToEnd == 0 || rightToRight == 0) {
                if (isChevronImageView(child)) {
                    sChevronImageIds.add(child.getId());
                    return child;
                } else if (child instanceof FrameLayout) {
                    FrameLayout fl = (FrameLayout) child;
                    for (int j = 0; j < fl.getChildCount(); j++) {
                        View sub = fl.getChildAt(j);
                        if (isChevronImageView(sub)) {
                            sChevronContainerIds.add(child.getId());
                            sChevronImageIds.add(sub.getId());
                            return sub;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean isCloseOrDismissButton(View v) {
        if (v == null) return false;
        CharSequence cd = v.getContentDescription();
        if (cd != null) {
            String cds = cd.toString().toLowerCase();
            if (cds.contains("close") || cds.contains("cancel") || cds.contains("关闭") || cds.contains("dismiss")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Inspects a view hierarchy and injects the eye toggle icon directly into the card's ConstraintLayout container
     * (as a sibling to the chevron arrow), positioning it immediately to the left of the chevron arrow.
     * Strictly executes ONLY for the top active account card that contains the chevron arrow,
     * and strictly ignores all account items in the expanded account list as well as the dialog header close button.
     */
    private static synchronized void checkAndInjectToggleIcon(final View root) {
        if (root == null) {
            return;
        }

        ensureDiscovery(root.getContext());

        // Strictly reject any view inside an account list (RecyclerView / ListView)
        if (isInsideAccountList(root)) {
            return;
        }

        // 1. Locate the chevron arrow View strictly via verified chevron IDs
        View chevronView = findChevronView(root);

        // 2. If not found by ID, attempt strictly scoped fallback within verified account containers ONLY
        if (chevronView == null) {
            if (root instanceof ViewGroup && isAccountContainer((ViewGroup) root)) {
                chevronView = findChevronInsideAccountContainer((ViewGroup) root);
            } else {
                ViewParent p = root.getParent();
                while (p != null) {
                    if (p instanceof ViewGroup && isAccountContainer((ViewGroup) p) && !isInsideAccountList((View) p)) {
                        chevronView = findChevronInsideAccountContainer((ViewGroup) p);
                        if (chevronView != null) break;
                    }
                    p = p.getParent();
                }
            }
        }

        if (chevronView == null) {
            return;
        }

        if (isInsideAccountList(chevronView)) {
            return;
        }

        // 3. Resolve target container (ConstraintLayout) and target anchor View
        View targetAnchor = chevronView;
        ViewGroup targetContainer = null;

        ViewParent p = chevronView.getParent();
        ViewParent gp = (p != null) ? p.getParent() : null;

        if (p instanceof ViewGroup) {
            ViewGroup pvg = (ViewGroup) p;
            int pId = pvg.getId();
            // If chevron is wrapped inside FrameLayout container (e.g. 0x7f0b09e5 in legacy 2PP.xml)
            if (sChevronContainerIds.contains(pId) || (pvg instanceof FrameLayout && gp instanceof ViewGroup && isConstraintLayout((ViewGroup) gp))) {
                targetAnchor = pvg;
                targetContainer = (ViewGroup) gp;
            } else if (isConstraintLayout(pvg)) {
                targetAnchor = chevronView;
                targetContainer = pvg;
            } else if (gp instanceof ViewGroup && isConstraintLayout((ViewGroup) gp)) {
                targetAnchor = pvg;
                targetContainer = (ViewGroup) gp;
            } else {
                targetAnchor = chevronView;
                targetContainer = pvg;
            }
        }

        if (targetAnchor == null || targetContainer == null) {
            return;
        }

        // Double lock: targetContainer MUST belong to the active account card (NOT a dialog toolbar or close container)
        if (!isAccountContainer(targetContainer)) {
            return;
        }

        if (isInsideAccountList(targetContainer)) {
            return;
        }

        // Prevent duplicate injection in the target container
        if (targetContainer.findViewWithTag(TAG_TOGGLE_BUTTON) != null) {
            return;
        }

        int anchorId = targetAnchor.getId();
        if (anchorId == View.NO_ID) {
            anchorId = View.generateViewId();
            targetAnchor.setId(anchorId);
        }

        final Context context = targetContainer.getContext();
        if (!sPrefLoaded) {
            sIsMasked = getMaskPreference(context);
            sPrefLoaded = true;
        }

        // 4. Create toggle ImageView
        final ImageView toggleIcon = new ImageView(context);
        final int toggleId = View.generateViewId();
        toggleIcon.setId(toggleId);
        toggleIcon.setTag(TAG_TOGGLE_BUTTON);

        int sizePx = dpToPx(context, 24);
        int padPx = dpToPx(context, 2);
        int gapPx = dpToPx(context, 8);
        int goneMarginPx = dpToPx(context, 16);

        toggleIcon.setPadding(padPx, padPx, padPx, padPx);
        toggleIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);

        final MaskDrawable drawable = new MaskDrawable(sIsMasked);
        ColorStateList tint = resolveIconTint(context, targetAnchor);
        toggleIcon.setImageTintList(tint);
        toggleIcon.setImageDrawable(drawable);

        // Borderless circular ripple
        TypedValue outVal = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outVal, true)) {
            toggleIcon.setBackgroundResource(outVal.resourceId);
        }
        toggleIcon.setClickable(true);
        toggleIcon.setFocusable(true);
        toggleIcon.setContentDescription("Toggle email mask");

        // Interactive click listener: micro bounce & real-time mask toggle
        toggleIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.animate().scaleX(0.82f).scaleY(0.82f).setDuration(70)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(70).start();
                        }
                    }).start();

                toggleMaskState(context);
            }
        });

        // 5. Generate LayoutParams dynamically to avoid R8 ClassNotFoundException
        ViewGroup.MarginLayoutParams lp = null;
        try {
            lp = (ViewGroup.MarginLayoutParams) XposedHelpers.callMethod(targetContainer, "generateDefaultLayoutParams");
        } catch (Throwable t1) {
            try {
                ViewGroup.LayoutParams anchorLp = targetAnchor.getLayoutParams();
                if (anchorLp != null) {
                    Class<?> lpClass = anchorLp.getClass();
                    lp = (ViewGroup.MarginLayoutParams) lpClass.getConstructor().newInstance();
                }
            } catch (Throwable t2) {
                try {
                    ViewGroup.LayoutParams anchorLp = targetAnchor.getLayoutParams();
                    if (anchorLp != null) {
                        Class<?> lpClass = anchorLp.getClass();
                        lp = (ViewGroup.MarginLayoutParams) lpClass.getConstructor(int.class, int.class).newInstance(sizePx, sizePx);
                    }
                } catch (Throwable t3) {
                    lp = new ViewGroup.MarginLayoutParams(sizePx, sizePx);
                }
            }
        }
        if (lp == null) {
            lp = new ViewGroup.MarginLayoutParams(sizePx, sizePx);
        }
        lp.width = sizePx;
        lp.height = sizePx;

        // 6. Set constraints (supporting both standard and R8 obfuscated single-letter fields)
        if (isConstraintLayout(targetContainer)) {
            // Horizontal: right side anchored to targetAnchor's left side
            setField(lp, "endToStart", anchorId);
            setField(lp, "u", anchorId);
            setField(lp, "rightToLeft", anchorId);
            setField(lp, "an", anchorId);

            // Margins
            lp.setMarginEnd(gapPx);
            setField(lp, "goneEndMargin", goneMarginPx);
            setField(lp, "B", goneMarginPx);
            setField(lp, "goneRightMargin", goneMarginPx);
            setField(lp, "ap", goneMarginPx);

            // Vertical: align centered with targetAnchor
            setField(lp, "topToTop", anchorId);
            setField(lp, "i", anchorId);
            setField(lp, "bottomToBottom", anchorId);
            setField(lp, "l", anchorId);

            try {
                lp.resolveLayoutDirection(targetContainer.getLayoutDirection());
            } catch (Throwable ignored) {}

            try {
                Method m = lp.getClass().getMethod("a");
                m.invoke(lp);
            } catch (Throwable ignored) {}

            // Update siblings anchored to targetAnchor to anchor to toggleIcon instead (prevents text overlap)
            for (int i = 0; i < targetContainer.getChildCount(); i++) {
                View child = targetContainer.getChildAt(i);
                if (child == toggleIcon || child == targetAnchor) continue;
                ViewGroup.LayoutParams clp = child.getLayoutParams();
                if (clp == null) continue;

                int curEnd = getField(clp, "endToStart", getField(clp, "u", -1));
                if (curEnd == anchorId) {
                    setField(clp, "endToStart", toggleId);
                    setField(clp, "u", toggleId);
                    setField(clp, "rightToLeft", toggleId);
                    setField(clp, "an", toggleId);
                    try {
                        clp.resolveLayoutDirection(targetContainer.getLayoutDirection());
                    } catch (Throwable ignored) {}
                    child.setLayoutParams(clp);
                }
            }
            targetContainer.addView(toggleIcon, lp);
        } else if (targetContainer instanceof android.widget.LinearLayout) {
            int anchorIdx = targetContainer.indexOfChild(targetAnchor);
            lp.setMarginEnd(gapPx);
            targetContainer.addView(toggleIcon, Math.max(0, anchorIdx), lp);
        } else if (targetContainer instanceof FrameLayout) {
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(sizePx, sizePx, Gravity.CENTER_VERTICAL | Gravity.END);
            int anchorW = targetAnchor.getWidth() > 0 ? targetAnchor.getWidth() : sizePx;
            flp.setMarginEnd(anchorW + gapPx);
            targetContainer.addView(toggleIcon, flp);
        } else {
            lp.setMarginEnd(gapPx);
            targetContainer.addView(toggleIcon, lp);
        }

        synchronized (sTrackedToggleIcons) {
            sTrackedToggleIcons.add(toggleIcon);
        }

        // Keep tint synchronized if theme updates
        final View anchorViewFinal = targetAnchor;
        targetContainer.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                ColorStateList updatedTint = resolveIconTint(context, anchorViewFinal);
                if (updatedTint != null && !updatedTint.equals(toggleIcon.getImageTintList())) {
                    toggleIcon.setImageTintList(updatedTint);
                }
            }
        });

        targetContainer.requestLayout();
        targetContainer.invalidate();

        // 7. Track and mask pre-existing emails in the container and root
        trackAndMaskEmailsInView(targetContainer);
        if (root != targetContainer) {
            trackAndMaskEmailsInView(root);
        }

        XposedBridge.log(TAG + ": Injected toggle icon into targetContainer (" + targetContainer.getClass().getSimpleName() + ") sibling to anchor (" + anchorId + ")");
    }

    private static void hookLayoutInflater(ClassLoader classLoader) {
        try {
            XposedBridge.hookAllMethods(LayoutInflater.class, "inflate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object res = param.getResult();
                    if (res instanceof View) {
                        final View v = (View) res;
                        checkAndInjectToggleIcon(v);
                        v.post(new Runnable() {
                            @Override
                            public void run() {
                                checkAndInjectToggleIcon(v);
                            }
                        });
                    }
                }
            });
            XposedBridge.log(TAG + ": Hooked LayoutInflater.inflate successfully");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to hook LayoutInflater.inflate: " + t);
        }
    }

    private static void hookSelectedAccountView(ClassLoader classLoader) {
        try {
            Class<?> viewClass = XposedHelpers.findClassIfExists(
                "com.google.android.libraries.onegoogle.accountmanagement.SelectedAccountView",
                classLoader
            );
            if (viewClass != null) {
                XposedBridge.hookAllConstructors(viewClass, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        final ViewGroup selectedAccountView = (ViewGroup) param.thisObject;
                        checkAndInjectToggleIcon(selectedAccountView);
                        selectedAccountView.post(new Runnable() {
                            @Override
                            public void run() {
                                checkAndInjectToggleIcon(selectedAccountView);
                            }
                        });
                    }
                });
                XposedBridge.log(TAG + ": Hooked SelectedAccountView constructors successfully");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to hook SelectedAccountView: " + t);
        }
    }

    private static void hookTextView(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                TextView.class,
                "setText",
                CharSequence.class,
                TextView.BufferType.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (sIsUpdating.get()) {
                            return;
                        }
                        CharSequence cs = (CharSequence) param.args[0];
                        if (cs == null || cs.length() == 0) {
                            return;
                        }
                        final TextView tv = (TextView) param.thisObject;
                        String textStr = cs.toString();

                        if (isAccountView(tv) && EmailMaskUtils.containsEmail(textStr)) {
                            // Store original unmasked email
                            String extraOrig = (String) XposedHelpers.getAdditionalInstanceField(tv, EXTRA_FIELD_ORIG_EMAIL);
                            String realEmail = (extraOrig != null) ? extraOrig : textStr;
                            if (EmailMaskUtils.containsEmail(textStr) && !textStr.contains("***")) {
                                realEmail = textStr;
                                XposedHelpers.setAdditionalInstanceField(tv, EXTRA_FIELD_ORIG_EMAIL, realEmail);
                            }
                            sOriginalEmails.put(tv, realEmail);

                            if (sIsMasked) {
                                param.args[0] = EmailMaskUtils.maskEmail(realEmail);
                            }

                            // Trigger toggle icon injection on tv and rootView
                            checkAndInjectToggleIcon(tv);
                            final View rootView = tv.getRootView();
                            if (rootView != null) {
                                rootView.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        checkAndInjectToggleIcon(rootView);
                                    }
                                });
                            }
                            if (tv.getParent() instanceof View) {
                                final View parentView = (View) tv.getParent();
                                parentView.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        checkAndInjectToggleIcon(parentView);
                                    }
                                });
                            }
                        }
                    }
                }
            );
            XposedBridge.log(TAG + ": Hooked TextView.setText successfully");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to hook TextView.setText: " + t);
        }
    }
}
