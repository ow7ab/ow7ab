; PMP PAINTS ERP — NSIS Installer Custom Hooks
; Ensures user settings, window state, and encrypted session metadata in %APPDATA%
; are preserved across upgrades and normal installations.

!macro customInstall
  ; Preserve existing userData directory if upgrading
  IfFileExists "$APPDATA\pmp-paints-desktop\*.*" 0 +2
    DetailPrint "Preserving existing PMP PAINTS ERP user settings and preferences."
!macroend

!macro customUnInstall
  ; Keep userData intact unless manually removed by administrator
  DetailPrint "Uninstalling PMP PAINTS ERP application binaries while preserving local preferences."
!macroend
