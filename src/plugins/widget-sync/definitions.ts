export interface WidgetSyncPlugin {
  /**
   * Syncs habit data to native SharedPreferences so the Android home-screen
   * widget can read it without launching the WebView.
   *
   * @param options.folderUri  - The SAF tree URI for the habits folder.
   * @param options.habitsJson - JSON string: Array<{ id, file, name, icon, target }>
   */
  syncHabits(options: { folderUri: string; habitsJson: string }): Promise<void>
}
