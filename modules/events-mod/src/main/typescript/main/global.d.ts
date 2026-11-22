declare const storage: {
  MOD_VERSION: string
  FACTORIO_VERSION: string
  /** The production statistics totals that were last sent, by `force/surface` */
  productionTotals?: Record<string, unknown>
}
