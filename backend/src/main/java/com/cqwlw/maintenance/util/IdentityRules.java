package com.cqwlw.maintenance.util;

/**
 * 人员身份比对口径的唯一出处。工单归属、定位申诉提交、围栏放行三处都依赖同一条规则，
 * 各自内联一份必然逐渐分叉，故抽出为共享工具。
 */
public final class IdentityRules {

    private IdentityRules() {
    }

    /**
     * 姓名与 platform_id 必须同时匹配，任一为空即拒绝。
     * 严禁退化为只按姓名或只按 ID 匹配——姓名会重名，两者必须指向同一个人。
     */
    public static boolean matches(String myPlatformId, String myName,
                                  String targetPlatformId, String targetName) {
        if (isBlank(myPlatformId) || isBlank(targetPlatformId)
                || isBlank(myName) || isBlank(targetName)) {
            return false;
        }
        return myPlatformId.trim().equals(targetPlatformId.trim())
                && myName.trim().equals(targetName.trim());
    }

    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}