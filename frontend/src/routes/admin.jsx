// Admin portal: pages administrators use to manage users, inventory, policies and reports.
// App.jsx mounts these routes and Layout.jsx shows the nav entries for the matching role.

/** Page shown at / for this role, or null to use the generic home page. */
export const home = null;

/** Routes only this role can open: [path, element]. */
export const routes = [];

/** Sidebar entries: [link, label]. */
export const nav = [];
