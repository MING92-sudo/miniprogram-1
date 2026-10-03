package com.cqwlw.maintenance.util;

/**
 * 签到定位距离计算（docs/02 §5.4：Haversine 球面距离，签到坐标 vs 电梯档案登记坐标）。
 */
public final class GeoUtil {

    private static final double EARTH_RADIUS_METERS = 6371000d;

    private GeoUtil() {
    }

    /** 两点球面距离（米）；任一坐标缺失返回 -1，表示不可计算（调用方按 UNKNOWN 降级处理） */
    public static double distanceMeters(Double lat1, Double lng1, Double lat2, Double lng2) {
        if (lat1 == null || lng1 == null || lat2 == null || lng2 == null) {
            return -1d;
        }
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1d, Math.sqrt(a)));
    }
}
