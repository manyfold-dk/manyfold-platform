/**
 * The Manyfold mark: the platform stack, four rounded bars in a 40 x 40 box, from
 * the top tenants, network, cluster and infrastructure. The geometry is fixed: do
 * not reorder, recolour or rotate the bars, and never add a fifth.
 */
export const MarkBars = () => (
  <g>
    <rect x="0" y="0" width="40" height="7" rx="2" fill="#93C5FD" />
    <rect x="0" y="11" width="40" height="7" rx="2" fill="#60A5FA" />
    <rect x="0" y="22" width="40" height="7" rx="2" fill="#2563EB" />
    <rect x="0" y="33" width="40" height="7" rx="2" fill="#1E40AF" />
  </g>
);
