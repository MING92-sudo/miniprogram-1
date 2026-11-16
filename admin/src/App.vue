<template>
  <!-- 内联 SVG sprite：与原型 UI/admin/assets/icons.svg 完全一致（离线零外部请求） -->
  <svg id="sprite" xmlns="http://www.w3.org/2000/svg" style="display:none" aria-hidden="true">
    <symbol id="i-gauge" viewBox="0 0 24 24"><path d="M3.5 18a8.5 8.5 0 1 1 17 0"/><path d="M12 18l4.4-5.2"/><circle cx="12" cy="18" r="1.3"/></symbol>
    <symbol id="i-sitemap" viewBox="0 0 24 24"><rect x="9" y="2.5" width="6" height="4.5" rx="1"/><rect x="2.5" y="17" width="6" height="4.5" rx="1"/><rect x="15.5" y="17" width="6" height="4.5" rx="1"/><path d="M12 7v4M5.5 17v-3h13v3"/></symbol>
    <symbol id="i-users" viewBox="0 0 24 24"><circle cx="9" cy="8" r="3.2"/><path d="M2.8 19.5a6.2 6.2 0 0 1 12.4 0"/><path d="M16.5 5.6a3.2 3.2 0 0 1 0 5.4M18 14.4a6.2 6.2 0 0 1 3.2 5.1"/></symbol>
    <symbol id="i-user" viewBox="0 0 24 24"><circle cx="12" cy="8" r="3.6"/><path d="M5 19.5a7 7 0 0 1 14 0"/></symbol>
    <symbol id="i-shield" viewBox="0 0 24 24"><path d="M12 2.8l7.2 2.8v6.1c0 4.2-3 7.4-7.2 9.5-4.2-2.1-7.2-5.3-7.2-9.5V5.6z"/><path d="M8.8 11.8l2.4 2.4 4.2-4.6"/></symbol>
    <symbol id="i-key" viewBox="0 0 24 24"><circle cx="7.5" cy="8.5" r="3.8"/><path d="M10.4 11.2L20 20.6M16.4 17l2.2-2.2M13.6 14.2l2.4-2.4"/></symbol>
    <symbol id="i-building" viewBox="0 0 24 24"><path d="M4 21V4.5A1.5 1.5 0 0 1 5.5 3h9A1.5 1.5 0 0 1 16 4.5V21"/><path d="M16 9h2.5A1.5 1.5 0 0 1 20 10.5V21M2.5 21h19"/><path d="M7.5 7h2M7.5 11h2M7.5 15h2M12 7h1.5M12 11h1.5"/></symbol>
    <symbol id="i-file-text" viewBox="0 0 24 24"><path d="M14 2.8H6.8A1.8 1.8 0 0 0 5 4.6v14.8a1.8 1.8 0 0 0 1.8 1.8h10.4a1.8 1.8 0 0 0 1.8-1.8V7.8z"/><path d="M14 2.8V7.8h4.8M8.4 12.5h7.2M8.4 16h5"/></symbol>
    <symbol id="i-file-sign" viewBox="0 0 24 24"><path d="M13.5 2.8H6.8A1.8 1.8 0 0 0 5 4.6v14.8a1.8 1.8 0 0 0 1.8 1.8h6"/><path d="M13.5 2.8V7.8h4.8M8.4 11h5M8.4 14.4h3.4"/><path d="M14.6 20.4c1.4-3.2 2.6-4.6 3.6-4.2 1 .4.2 2.2-.6 3.4-.6.9 1.4 1.6 3.4-.6"/></symbol>
    <symbol id="i-file-check" viewBox="0 0 24 24"><path d="M13.5 2.8H6.8A1.8 1.8 0 0 0 5 4.6v14.8a1.8 1.8 0 0 0 1.8 1.8h6"/><path d="M13.5 2.8V7.8h4.8M8.4 11h5M8.4 14.4h3.4"/><path d="M15.4 17.6l1.8 1.8 3.4-3.8"/></symbol>
    <symbol id="i-elevator" viewBox="0 0 24 24"><rect x="4" y="2.8" width="16" height="18.4" rx="1.6"/><path d="M12 2.8v18.4M7.6 13.4V9.6M6.2 10.8l1.4-1.6 1.4 1.6M16.4 10.6v3.8M15 13.2l1.4 1.6 1.4-1.6"/></symbol>
    <symbol id="i-exchange" viewBox="0 0 24 24"><path d="M3.5 8.5h13M13.5 5l3.5 3.5-3.5 3.5M20.5 15.5h-13M10.5 12l-3.5 3.5 3.5 3.5"/></symbol>
    <symbol id="i-calendar" viewBox="0 0 24 24"><rect x="3.2" y="5" width="17.6" height="16" rx="1.8"/><path d="M3.2 10h17.6M8 3v4M16 3v4"/><path d="M7.5 14h2M11 14h2M14.5 14h2M7.5 17.5h2M11 17.5h2"/></symbol>
    <symbol id="i-clipboard" viewBox="0 0 24 24"><path d="M9 4.5H6.8A1.8 1.8 0 0 0 5 6.3v13A1.8 1.8 0 0 0 6.8 21h10.4a1.8 1.8 0 0 0 1.8-1.7v-13a1.8 1.8 0 0 0-1.8-1.8H15"/><rect x="9" y="2.6" width="6" height="3.8" rx="1.2"/><path d="M8.6 13.4l2 2 4.6-5"/></symbol>
    <symbol id="i-bolt" viewBox="0 0 24 24"><path d="M13.2 2.5L4.8 13.4h5.6l-.6 8.1 8.4-11h-5.6z"/></symbol>
    <symbol id="i-inbox" viewBox="0 0 24 24"><path d="M3 13.5l2.6-8.2A1.8 1.8 0 0 1 7.3 4h9.4a1.8 1.8 0 0 1 1.7 1.3L21 13.5V19a1.8 1.8 0 0 1-1.8 1.8H4.8A1.8 1.8 0 0 1 3 19z"/><path d="M3 13.5h5l1.2 2.4h5.6L16 13.5h5"/></symbol>
    <symbol id="i-chart" viewBox="0 0 24 24"><path d="M3.5 20.5h17"/><rect x="5" y="12" width="3.4" height="6"/><rect x="10.3" y="7.5" width="3.4" height="10.5"/><rect x="15.6" y="10" width="3.4" height="8"/></symbol>
    <symbol id="i-pie" viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.8"/><path d="M12 12V3.2M12 12l6.9 5.5"/></symbol>
    <symbol id="i-history" viewBox="0 0 24 24"><path d="M3.6 12a8.4 8.4 0 1 0 2.6-6.1L3.4 8.4"/><path d="M3.2 4.4v4.2h4.2M12 7.8V12l3.2 2"/></symbol>
    <symbol id="i-sliders" viewBox="0 0 24 24"><path d="M4 7h9M17.5 7H20M4 12h3M11.5 12H20M4 17h9M17.5 17H20"/><circle cx="15.2" cy="7" r="2.1"/><circle cx="9.2" cy="12" r="2.1"/><circle cx="15.2" cy="17" r="2.1"/></symbol>
    <symbol id="i-palette" viewBox="0 0 24 24"><path d="M12 3.2a8.8 8.8 0 0 0 0 17.6c1.3 0 1.9-.9 1.9-1.8 0-1.6-1.4-1.7-1.4-3 0-.9.7-1.6 1.7-1.6h1.9a4.7 4.7 0 0 0 4.7-4.7c0-3.7-3.9-6.5-8.8-6.5z"/><circle cx="7.6" cy="11" r="1.2"/><circle cx="10.4" cy="7.2" r="1.2"/><circle cx="15" cy="8.4" r="1.2"/></symbol>
    <symbol id="i-search" viewBox="0 0 24 24"><circle cx="10.8" cy="10.8" r="6.6"/><path d="M15.6 15.6L20.5 20.5"/></symbol>
    <symbol id="i-filter" viewBox="0 0 24 24"><path d="M3.5 5.2h17l-6.6 7.6V20l-3.8-2.2v-5z"/></symbol>
    <symbol id="i-sort" viewBox="0 0 24 24"><path d="M8 4.5v15M8 4.5L5 8M8 4.5L11 8M16 19.5v-15M16 19.5L13 16M16 19.5L19 16"/></symbol>
    <symbol id="i-chevron-down" viewBox="0 0 24 24"><path d="M5.5 9l6.5 6.5L18.5 9"/></symbol>
    <symbol id="i-chevron-right" viewBox="0 0 24 24"><path d="M9 5.5L15.5 12 9 18.5"/></symbol>
    <symbol id="i-chevron-left" viewBox="0 0 24 24"><path d="M15 5.5L8.5 12 15 18.5"/></symbol>
    <symbol id="i-arrow-right" viewBox="0 0 24 24"><path d="M4 12h15M14 6.5l5.5 5.5L14 17.5"/></symbol>
    <symbol id="i-plus" viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></symbol>
    <symbol id="i-minus" viewBox="0 0 24 24"><path d="M5 12h14"/></symbol>
    <symbol id="i-check" viewBox="0 0 24 24"><path d="M4.5 12.5l5 5L19.5 6.5"/></symbol>
    <symbol id="i-x" viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></symbol>
    <symbol id="i-alert" viewBox="0 0 24 24"><path d="M12 3.4L21.2 20H2.8z"/><path d="M12 9v5M12 17h.01"/></symbol>
    <symbol id="i-clock" viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.8"/><path d="M12 6.8V12l3.6 2.2"/></symbol>
    <symbol id="i-download" viewBox="0 0 24 24"><path d="M12 3.5v11M12 14.5l-4-4M12 14.5l4-4M4 19.5h16"/></symbol>
    <symbol id="i-upload" viewBox="0 0 24 24"><path d="M12 20.5v-11M12 9.5l-4 4M12 9.5l4 4M4 4.5h16"/></symbol>
    <symbol id="i-print" viewBox="0 0 24 24"><path d="M7 8.5V3.5h10v5"/><rect x="3.5" y="8.5" width="17" height="8" rx="1.6"/><path d="M7 14.5h10V21H7z"/></symbol>
    <symbol id="i-qr" viewBox="0 0 24 24"><rect x="3.2" y="3.2" width="6.6" height="6.6" rx="1"/><rect x="14.2" y="3.2" width="6.6" height="6.6" rx="1"/><rect x="3.2" y="14.2" width="6.6" height="6.6" rx="1"/><path d="M14.2 14.2h3v3h-3zM19.5 14.2h1.3M14.2 20.5h6.6M19.5 17.5v3"/></symbol>
    <symbol id="i-edit" viewBox="0 0 24 24"><path d="M4 20l1-4.5L16.2 4.3a2 2 0 0 1 2.8 0l.7.7a2 2 0 0 1 0 2.8L8.5 19z"/><path d="M14.5 6l3.5 3.5"/></symbol>
    <symbol id="i-eye" viewBox="0 0 24 24"><path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="3"/></symbol>
    <symbol id="i-eye-off" viewBox="0 0 24 24"><path d="M4 4l16 16"/><path d="M9.5 5.9A9.6 9.6 0 0 1 12 5.5c6 0 9.5 6.5 9.5 6.5a17 17 0 0 1-2.7 3.6M14.2 14.4a3 3 0 0 1-4.3-3.1M6.2 7.9A16.5 16.5 0 0 0 2.5 12S6 18.5 12 18.5a9.7 9.7 0 0 0 2.6-.35"/></symbol>
    <symbol id="i-lock" viewBox="0 0 24 24"><rect x="4.5" y="10" width="15" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3M12 14v3"/></symbol>
    <symbol id="i-hash" viewBox="0 0 24 24"><path d="M9 3.5L7.5 20.5M16.5 3.5L15 20.5M3.5 8.5h17M3 15.5h17"/></symbol>
    <symbol id="i-signature" viewBox="0 0 24 24"><path d="M3 18c2.2 0 3-1.5 3.6-4C7.4 10.4 8.2 6 10 6c1.4 0 1.2 2.4.8 4.6-.4 2.2-.8 4.4.6 4.4 1.8 0 2.4-3.4 4.6-3.4 1.4 0 1.6 1.6 1 3.4M4.5 21h15"/></symbol>
    <symbol id="i-external" viewBox="0 0 24 24"><path d="M14 4h6v6M20 4l-8.5 8.5M18 14v4.5A1.5 1.5 0 0 1 16.5 20h-11A1.5 1.5 0 0 1 4 18.5v-11A1.5 1.5 0 0 1 5.5 6H10"/></symbol>
    <symbol id="i-logout" viewBox="0 0 24 24"><path d="M14 4H6.5A1.5 1.5 0 0 0 5 5.5v13A1.5 1.5 0 0 0 6.5 20H14"/><path d="M17 8.5l3.5 3.5L17 15.5M20 12H10"/></symbol>
    <symbol id="i-panel" viewBox="0 0 24 24"><rect x="3" y="4.5" width="18" height="15" rx="1.8"/><path d="M9.5 4.5v15"/></symbol>
    <symbol id="i-refresh" viewBox="0 0 24 24"><path d="M20 12a8 8 0 1 1-2.4-5.7"/><path d="M20.5 3.5V8H16"/></symbol>
    <symbol id="i-phone" viewBox="0 0 24 24"><path d="M6.4 3.5h3l1.5 4-2 1.6a11 11 0 0 0 5.4 5.4l1.6-2 4 1.5v3a1.8 1.8 0 0 1-2 1.8A15.6 15.6 0 0 1 4.6 5.5a1.8 1.8 0 0 1 1.8-2z"/></symbol>
    <symbol id="i-mail" viewBox="0 0 24 24"><rect x="3" y="5.5" width="18" height="13" rx="1.8"/><path d="M3.6 6.8L12 13l8.4-6.2"/></symbol>
    <symbol id="i-info" viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.8"/><path d="M12 11v5.5M12 7.8h.01"/></symbol>
    <symbol id="i-link" viewBox="0 0 24 24"><path d="M10.5 13.5a4 4 0 0 1 0-5.7l2.4-2.4a4 4 0 0 1 5.7 5.7l-1.3 1.3"/><path d="M13.5 10.5a4 4 0 0 1 0 5.7l-2.4 2.4a4 4 0 0 1-5.7-5.7l1.3-1.3"/></symbol>
    <symbol id="i-image" viewBox="0 0 24 24"><rect x="3.2" y="4.5" width="17.6" height="15" rx="1.8"/><circle cx="8.8" cy="9.8" r="1.8"/><path d="M4 17l4.8-4.6 3.4 3.2 3-2.8 4.6 4.2"/></symbol>
    <symbol id="i-ban" viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.8"/><path d="M5.8 18.2L18.2 5.8"/></symbol>
    <symbol id="i-tag" viewBox="0 0 24 24"><path d="M20.5 12.7l-7.8 7.8a2 2 0 0 1-2.8 0L3.5 13.6V3.5H13.6l6.9 6.4a2 2 0 0 1 0 2.8z"/><circle cx="8.2" cy="8.2" r="1.5"/></symbol>
    <symbol id="i-wrench" viewBox="0 0 24 24"><path d="M15.4 3.4a5.6 5.6 0 0 0-1 8.2L4 21.5l-1.5-1.5L12.4 9.6a5.6 5.6 0 0 0 7.6-6.2l-3 3-2.3-2.3z"/></symbol>
    <symbol id="i-bell" viewBox="0 0 24 24"><path d="M18 15.5V10a6 6 0 1 0-12 0v5.5L4.5 18h15z"/><path d="M10 20.8a2.2 2.2 0 0 0 4 0"/></symbol>
    <symbol id="i-activity" viewBox="0 0 24 24"><path d="M3 12.5h4l2.5-7 4 14 2.5-7H21"/></symbol>
    <symbol id="i-database" viewBox="0 0 24 24"><ellipse cx="12" cy="6" rx="8" ry="3.2"/><path d="M4 6v12c0 1.8 3.6 3.2 8 3.2s8-1.4 8-3.2V6M4 12c0 1.8 3.6 3.2 8 3.2s8-1.4 8-3.2"/></symbol>
    <symbol id="i-list" viewBox="0 0 24 24"><path d="M8.5 6.5H21M8.5 12H21M8.5 17.5H21M3.5 6.5h2M3.5 12h2M3.5 17.5h2"/></symbol>
    <symbol id="i-layers" viewBox="0 0 24 24"><path d="M12 3l8.5 4.6L12 12.2 3.5 7.6z"/><path d="M3.5 12.4L12 17l8.5-4.6M3.5 16.6L12 21.2l8.5-4.6"/></symbol>
    <symbol id="i-map-pin" viewBox="0 0 24 24"><path d="M12 21.2s7-6 7-11.2a7 7 0 1 0-14 0c0 5.2 7 11.2 7 11.2z"/><circle cx="12" cy="10" r="2.6"/></symbol>
    <symbol id="i-user-check" viewBox="0 0 24 24"><circle cx="10" cy="8" r="3.5"/><path d="M3.5 20a6.5 6.5 0 0 1 13 0"/><path d="M16.5 15.5l2 2 4-4.4"/></symbol>
    <symbol id="i-user-x" viewBox="0 0 24 24"><circle cx="10" cy="8" r="3.5"/><path d="M3.5 20a6.5 6.5 0 0 1 10.2-5.3"/><path d="M16 16l4.5 4.5M20.5 16L16 20.5"/></symbol>
    <symbol id="i-folder" viewBox="0 0 24 24"><path d="M3.5 6.5A1.8 1.8 0 0 1 5.3 4.7h4l2.2 2.6h7.2a1.8 1.8 0 0 1 1.8 1.8v9.4a1.8 1.8 0 0 1-1.8 1.8H5.3a1.8 1.8 0 0 1-1.8-1.8z"/></symbol>
    <symbol id="i-weixin" viewBox="0 0 24 24"><path d="M9.2 4.2c-3.8 0-6.9 2.5-6.9 5.6 0 1.8 1 3.4 2.6 4.5l-.7 2 2.3-1.2c.9.3 1.8.4 2.7.4h.5a4.6 4.6 0 0 1-.2-1.4c0-3 2.9-5.4 6.5-5.4h.6C16 6 12.9 4.2 9.2 4.2z"/><path d="M21.7 14.2c0-2.5-2.4-4.5-5.4-4.5s-5.4 2-5.4 4.5 2.4 4.5 5.4 4.5c.7 0 1.3-.1 1.9-.3l2 1.1-.6-1.7a4.3 4.3 0 0 0 2.1-3.6z"/></symbol>
    <symbol id="i-clipboard-list" viewBox="0 0 24 24"><path d="M9 4.5H6.8A1.8 1.8 0 0 0 5 6.3v13A1.8 1.8 0 0 0 6.8 21h10.4a1.8 1.8 0 0 0 1.8-1.7v-13a1.8 1.8 0 0 0-1.8-1.8H15"/><rect x="9" y="2.6" width="6" height="3.8" rx="1.2"/><path d="M8.5 11.5h7M8.5 15h5"/></symbol>
    <symbol id="i-archive" viewBox="0 0 24 24"><rect x="3" y="4" width="18" height="4.5" rx="1.2"/><path d="M4.8 8.5v9.7A1.8 1.8 0 0 0 6.6 20h10.8a1.8 1.8 0 0 0 1.8-1.8V8.5M9.8 12.5h4.4"/></symbol>
    <symbol id="i-cloud-upload" viewBox="0 0 24 24"><path d="M4 14.9A7 7 0 1 1 15.7 8h1.8a4.5 4.5 0 0 1 2.5 8.2"/><path d="M12 12v9M16 16l-4-4-4 4"/></symbol>
    <symbol id="i-star" viewBox="0 0 24 24"><path d="M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.9-5.2-2.7-5.2 2.7 1-5.9-4.3-4.1 5.9-.9z"/></symbol>
    <symbol id="i-message" viewBox="0 0 24 24"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/><path d="M8 9h8M8 13h5"/></symbol>
    <symbol id="i-monitor" viewBox="0 0 24 24"><rect x="3" y="4" width="18" height="13" rx="1.8"/><path d="M8.5 20.5h7M12 17v3.5"/></symbol>
  </svg>
  <div class="bg-grid" aria-hidden="true"></div>
  <div class="body-glow" aria-hidden="true"></div>
  <router-view />
</template>

<script setup>
// 全局外壳：深色工业风（原型 admin.css 逐字移植）+ 内联 SVG sprite + 网格/光晕背景
</script>

<style>
html,
body,
#app {
  height: 100%;
  margin: 0;
  background: var(--ink);
}
</style>