/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.messaging.rcs.rbm;

import java.util.List;
import java.util.Map;

/**
 * RBM agent BRAND info (Phase 3) — the verified-business identity for
 * an {@code <agent>@rbm.goog} bot, used to render the conversation's brand header
 * (name + logo + ✓ verified badge + hero/color).
 *
 * <p>Resolved from the PUBLIC unauthenticated GET
 * {@code https://rbm.goog/bot?id=sip:<botId>&hl=en&v=1.5&ho=<simMccMnc>}, whose
 * body is the GSMA PCC (Presence Content Container) shape
 * {@code {botinfo:{pcc:{org-details:{name,org-description,media-list,...}},
 * custom-pcc:{media-list:[HERO]}}, bot-verification:{verification-info:{...}}}}.
 * The image URLs ({@link #logoUrl}/{@link #heroUrl}/{@link #verifierLogoUrl}) are
 * PUBLIC {@code *.storage.googleapis.com} links (no auth/secret), so the main app
 * may fetch them directly when rendering (Phase 4).
 *
 * <p>This is a pure-Java value object; the parser is {@link #parse(String, String)}.
 */
public final class RbmBusinessInfo {

    /** The bot id this brand describes (e.g. {@code admin@rbm.goog}). */
    public final String botId;
    /** Display name, e.g. "RBM Tester Management". */
    public final String name;
    /** Org description; null if absent. */
    public final String description;
    /** Brand colour as {@code #rrggbb}; null if absent. */
    public final String color;
    /** Public logo image URL; null if absent. */
    public final String logoUrl;
    /** Public hero/banner image URL; null if absent. */
    public final String heroUrl;
    /** True when the agent carries a {@code bot-verification} block (✓ verified). */
    public final boolean verified;
    /** Verifier display ("verified-by", e.g. "Trusted Partner"); null if absent. */
    public final String verifierName;
    /** Public verifier-logo (the ✓ badge) image URL; null if absent. */
    public final String verifierLogoUrl;
    /** Agent use-case category, e.g. "TRANSACTIONAL"; null if absent. */
    public final String category;
    /** Contact phone (a {@code tel:} URI, stripped) + its display label; null if absent. */
    public final String phoneNumber;
    public final String phoneLabel;
    /** Contact email (a {@code mailto:} URI, stripped) + its display label; null if absent. */
    public final String email;
    public final String emailLabel;
    /** Website url + its display label; null if absent. */
    public final String website;
    public final String websiteLabel;
    /** Terms &amp; Conditions url; null if absent. */
    public final String termsUrl;
    /** Privacy-policy url; null if absent. */
    public final String privacyUrl;

    public RbmBusinessInfo(String botId, String name, String description, String color,
                           String logoUrl, String heroUrl, boolean verified,
                           String verifierName, String verifierLogoUrl,
                           String category, String phoneNumber, String phoneLabel,
                           String email, String emailLabel, String website,
                           String websiteLabel, String termsUrl, String privacyUrl) {
        this.botId = botId;
        this.name = name;
        this.description = description;
        this.color = color;
        this.logoUrl = logoUrl;
        this.heroUrl = heroUrl;
        this.verified = verified;
        this.verifierName = verifierName;
        this.verifierLogoUrl = verifierLogoUrl;
        this.category = category;
        this.phoneNumber = phoneNumber;
        this.phoneLabel = phoneLabel;
        this.email = email;
        this.emailLabel = emailLabel;
        this.website = website;
        this.websiteLabel = websiteLabel;
        this.termsUrl = termsUrl;
        this.privacyUrl = privacyUrl;
    }

    /**
     * Parse the {@code rbm.goog/bot} PCC JSON body into brand info.
     *
     * @param botId the bot id (carried through; the body does not always echo it).
     * @param json  the response body.
     * @throws Json.JsonException on malformed JSON.
     */
    public static RbmBusinessInfo parse(String botId, String json) throws Json.JsonException {
        Map<String, Object> root = Json.parseObject(json);

        Map<String, Object> orgDetails = asMap(path(root, "botinfo", "pcc", "org-details"));
        String name = asString(path(orgDetails, "name", "name-entry", "display-name"));
        String description = asString(get(orgDetails, "org-description"));

        String logoUrl = null;
        String color = null;
        for (Map<String, Object> me : mediaEntries(asMap(get(orgDetails, "media-list")))) {
            String mc = lower(asString(get(me, "media-content")));
            String label = lower(asString(get(me, "label")));
            Map<String, Object> media = asMap(get(me, "media"));
            if (media == null) continue;
            if ("logo".equals(mc) && logoUrl == null) {
                logoUrl = mediaUrl(media);
            } else if ("colour".equals(label) || "color".equals(label)
                    || (color == null && asString(get(media, "content")) != null
                        && asString(get(media, "content")).startsWith("#"))) {
                color = asString(get(media, "content"));
            }
        }

        // Hero lives under custom-pcc.media-list (media-content "HERO").
        String heroUrl = null;
        Map<String, Object> customPcc = asMap(path(root, "botinfo", "custom-pcc"));
        for (Map<String, Object> me : mediaEntries(asMap(get(customPcc, "media-list")))) {
            if ("hero".equals(lower(asString(get(me, "media-content"))))) {
                heroUrl = mediaUrl(asMap(get(me, "media")));
                if (heroUrl != null) break;
            }
        }

        // Verification block → verified badge.
        Map<String, Object> vinfo = asMap(path(root, "bot-verification", "verification-info"));
        boolean verified = vinfo != null;
        String verifierName = asString(get(vinfo, "verified-by"));
        String verifierLogoUrl = null;
        for (Map<String, Object> me : mediaEntries(asMap(get(vinfo, "media-list")))) {
            verifierLogoUrl = mediaUrl(asMap(get(me, "media")));
            if (verifierLogoUrl != null) break;
        }

        // Category: org-details.category-list.category-entry[0], else
        // custom-pcc.agent-use-case-category.
        String category = null;
        Object catEntry = path(orgDetails, "category-list", "category-entry");
        if (catEntry instanceof List && !((List<?>) catEntry).isEmpty()) {
            category = asString(((List<?>) catEntry).get(0));
        } else if (catEntry instanceof String) {
            category = (String) catEntry;
        }
        if (category == null) {
            category = asString(get(customPcc, "agent-use-case-category"));
        }

        // Contacts: comm-addr.uri-entry[] -> email (mailto:) + phone (tel:).
        String phone = null, phoneLabel = null, email = null, emailLabel = null;
        for (Map<String, Object> u : uriEntries(asMap(get(orgDetails, "comm-addr")))) {
            String uri = asString(get(u, "addr-uri"));
            if (uri == null) continue;
            String type = lower(asString(get(u, "addr-uri-type")));
            String low = uri.toLowerCase();
            String label = firstNonEmpty(asString(get(u, "custom-label")),
                    asString(get(u, "label")));
            if (email == null && ("email".equals(type) || low.startsWith("mailto:"))) {
                email = strip(uri, "mailto:");
                emailLabel = label;
            } else if (phone == null && (low.startsWith("tel:")
                    || (type != null && type.contains("phone"))
                    || "telephone".equals(type))) {
                phone = strip(uri, "tel:");
                phoneLabel = label;
            }
        }

        // Web resources (Website / Terms / Privacy), across org-details + custom-pcc.
        String website = null, websiteLabel = null, termsUrl = null, privacyUrl = null;
        for (Map<String, Object> w : webEntries(orgDetails, customPcc)) {
            String url = asString(get(w, "url"));
            if (url == null) continue;
            String label = lower(firstNonEmpty(asString(get(w, "label")),
                    asString(get(w, "custom-label"))));
            String disp = firstNonEmpty(asString(get(w, "custom-label")),
                    asString(get(w, "label")));
            if (website == null && label != null && label.contains("website")) {
                website = url;
                websiteLabel = disp;
            } else if (termsUrl == null && label != null
                    && (label.contains("tc") || label.contains("term"))) {
                termsUrl = url;
            } else if (privacyUrl == null && label != null && label.contains("privacy")) {
                privacyUrl = url;
            }
        }

        return new RbmBusinessInfo(botId, name, description, color, logoUrl, heroUrl,
                verified, verifierName, verifierLogoUrl, category, phone, phoneLabel,
                email, emailLabel, website, websiteLabel, termsUrl, privacyUrl);
    }

    public boolean hasLogo() { return logoUrl != null && !logoUrl.isEmpty(); }
    public boolean hasHero() { return heroUrl != null && !heroUrl.isEmpty(); }

    // ---- helpers ----

    /** A media object carries the url under "media-url" and/or "url"; prefer either. */
    private static String mediaUrl(Map<String, Object> media) {
        if (media == null) return null;
        String u = asString(get(media, "media-url"));
        if (u == null) u = asString(get(media, "url"));
        return u;
    }

    /** {@code comm-addr.uri-entry} is either an array OR a single object. */
    private static List<Map<String, Object>> uriEntries(Map<String, Object> commAddr) {
        return objList(commAddr, "uri-entry");
    }

    /** All {@code web-resources.web-entry} from org-details + custom-pcc. */
    private static List<Map<String, Object>> webEntries(Map<String, Object> orgDetails,
            Map<String, Object> customPcc) {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        out.addAll(objList(asMap(get(orgDetails, "web-resources")), "web-entry"));
        out.addAll(objList(asMap(get(customPcc, "web-resources")), "web-entry"));
        return out;
    }

    /** A child that is either an array of objects OR a single object. */
    private static List<Map<String, Object>> objList(Map<String, Object> parent, String key) {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (parent == null) return out;
        Object child = get(parent, key);
        if (child instanceof List) {
            for (Object o : (List<?>) child) {
                Map<String, Object> m = asMap(o);
                if (m != null) out.add(m);
            }
        } else {
            Map<String, Object> m = asMap(child);
            if (m != null) out.add(m);
        }
        return out;
    }

    /** Strip a scheme prefix (case-insensitive) from a URI, e.g. {@code mailto:}. */
    private static String strip(String uri, String prefix) {
        if (uri == null) return null;
        return uri.regionMatches(true, 0, prefix, 0, prefix.length())
                ? uri.substring(prefix.length()) : uri;
    }

    private static String firstNonEmpty(String a, String b) {
        return (a != null && !a.isEmpty()) ? a : b;
    }

    /** {@code media-list.media-entry} is either an array OR a single object. */
    private static List<Map<String, Object>> mediaEntries(Map<String, Object> mediaList) {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (mediaList == null) return out;
        Object entry = get(mediaList, "media-entry");
        if (entry instanceof List) {
            for (Object o : (List<?>) entry) {
                Map<String, Object> m = asMap(o);
                if (m != null) out.add(m);
            }
        } else {
            Map<String, Object> m = asMap(entry);
            if (m != null) out.add(m);
        }
        return out;
    }

    private static String lower(String s) { return s == null ? null : s.toLowerCase(); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }

    private static String asString(Object o) {
        return (o instanceof String) ? (String) o : null;
    }

    private static Object get(Map<String, Object> m, String k) {
        return m == null ? null : m.get(k);
    }

    private static Object path(Map<String, Object> m, String... keys) {
        Object cur = m;
        for (String k : keys) {
            cur = get(asMap(cur), k);
            if (cur == null) return null;
        }
        return cur;
    }

    @Override
    public String toString() {
        return "RbmBusinessInfo{botId=" + botId + ", name='" + name + "', color=" + color
                + ", verified=" + verified + " (" + verifierName + ")"
                + ", logo=" + (hasLogo() ? "y" : "n") + ", hero=" + (hasHero() ? "y" : "n") + "}";
    }
}
