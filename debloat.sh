#!/usr/bin/env bash
# Sony A80CJ (BRAVIA_VH2, Android 12 Google TV) debloat over ADB wifi.
# Usage: ./debloat.sh [debloat|restore]   (TV IP via TV=ip:port, default 192.168.50.143:5555)
# Everything here is per-user removal/disable: reversible with `restore` or a factory reset.
set -u
cd "$(dirname "$0")"
export PATH="$PWD/platform-tools:$PATH" ANDROID_SERIAL="${TV:-192.168.50.143:5555}"
adb connect "$ANDROID_SERIAL" >/dev/null

# From the cloned guide (sony-google-tv-debloat/README.md), only packages present on this TV.
README_PKGS=(
  com.sony.dtv.multiscreendemo com.sony.dtv.demosupport com.sony.dtv.smarthelp
  com.sony.dtv.b2b.hotelmode com.sony.dtv.b2b.prosettings com.sony.dtv.b2b.rs232csupport com.sony.dtv.b2b.vendorprotocol
  com.sony.dtv.sonybugreportsys com.sony.dtv.sonyloglevelsettingsys com.sony.dtv.customersupport com.sony.dtv.promos
  com.android.printspooler com.android.dreams.basic com.google.android.play.games
  com.android.providers.calendar com.android.providers.contacts com.android.providers.userdictionary
  com.android.wallpaperbackup com.android.vpndialogs com.google.android.syncadapters.calendar
  com.google.android.partnersetup com.google.android.feedback com.google.android.marvin.talkback com.google.android.videos
  tv.samba.ssm com.sony.dtv.osat.music com.sony.dtv.smartmediaapp com.android.captiveportallogin
  com.sony.dtv.interactivetvutil com.sony.dtv.seconddispsetting com.sony.dtv.homenetwork
)
# Extra removals: user does not use antenna, AirPlay/HomeKit, voice, or Sony extras.
EXTRA_PKGS=(
  tv.samba.ssm.ui com.sony.dtv.interactivetvutil.output
  com.sony.dtv.airplayapp com.sony.dtv.homekit
  com.sony.dtv.livingfit com.sony.dtv.distancealert com.sony.dtv.gestureapp
  com.sony.dtv.sensingcamera.fwupdate com.sony.dtv.sensingcamera.settings
  com.sony.dtv.ecodashboard com.sonypicturescore com.sony.dtv.sonyselect com.sony.dtv.frame com.sony.dtv.galleryenabler
  com.sony.dtv.seeds.iot com.sony.dtv.seeds.iot.remotestart
  com.sony.dtv.helpguidecontents com.sony.dtv.accessibility.tutorial
  com.sonyericsson.dlna com.sony.huey.dlna.module com.sony.huey.dlna.renderersettings
  com.sony.dtv.b2b.adminpassword com.sony.dtv.b2b.importexport com.sony.dtv.b2b.softap com.sony.dtv.b2b.webappruntimedrm
  com.sony.dtv.b2b.noderuntimeextension.bpk com.sony.dtv.b2b.noderuntimeextension.node_20_11_1
  com.sony.dtv.b2b.noderuntime.normal com.sony.dtv.b2b.noderuntime.privilege
  com.sony.dtv.tvinput.atsc3tuner com.sony.dtv.tvinput.atsc3tuner.drmlicenseinstaller
  com.sony.dtv.voicesearch com.sony.dtv.voicerecognitionconfirmation
  com.android.hotspot2.osulogin android.autoinstalls.config.sony.bravia
  # MediaTek telemetry agent. PERSISTENT: this build re-adds and starts it at every boot regardless
  # (needs root to stop for good); measured idle afterwards, 0 CPU. Kept so re-runs/restore stay consistent.
  com.mediatek.dmagent
)
# Less certain: disabled (not uninstalled) so they stay on disk.
DISABLE_PKGS=(
  com.sony.dtv.sonytab com.sony.dtv.da.service com.qterics.da.product com.sonyericsson.idd.agent
  com.sony.dtv.citool com.sony.dtv.hddmgr com.uei.quicksetsdk.sony com.sony.dtv.irblaster com.sony.dtv.irbservice
  com.sony.dtv.assistantadapter
  # background services the user doesn't use: MediaTek telemetry agent, antenna setup/clock,
  # Google Home TV control, Google TV phone remote, Sony network API + web-app runtime
  com.sony.dtv.broadcastservicesetup com.sony.dtv.broadcastclock.clocksyncservice
  com.sony.dtv.devicecontrolservice com.google.android.tv.remote.service
  com.sony.dtv.webapi.core com.sony.dtv.browser.webappruntime
)
# Never touch: boot loops or core features (README warnings + things this TV needs).
KEEP=(com.google.android.webview com.google.android.katniss com.sony.dtv.tvx com.google.android.tts
  com.android.location.fused com.google.android.apps.tv.launcherx com.google.android.apps.tv.dreamx)
for k in "${KEEP[@]}"; do
  [[ " ${README_PKGS[*]} ${EXTRA_PKGS[*]} ${DISABLE_PKGS[*]} " != *" $k "* ]] || { echo "BUG: $k is in a removal list"; exit 1; }
done

case "${1:-debloat}" in
debloat)
  # Removing/disabling an app also deletes the Wi-Fi networks it saved: never touch a network's owner.
  for o in $(adb shell dumpsys wifi | grep -oE 'cname=[A-Za-z0-9._]+' | cut -d= -f2 | sort -u); do
    [[ " ${README_PKGS[*]} ${EXTRA_PKGS[*]} ${DISABLE_PKGS[*]} " != *" $o "* ]] || {
      echo "Stopping: $o owns a saved Wi-Fi network. Forget and re-join it in Settings first."; exit 1; }
  done
  for p in "${README_PKGS[@]}" "${EXTRA_PKGS[@]}"; do echo "uninstall $p: $(adb shell pm uninstall --user 0 "$p" 2>&1)"; done
  for p in "${DISABLE_PKGS[@]}"; do echo "disable $p: $(adb shell pm disable-user --user 0 "$p" 2>&1)"; done
  adb shell settings put global window_animation_scale 0.5
  adb shell settings put global transition_animation_scale 0.5
  adb shell settings put global animator_duration_scale 0.5
  adb shell settings put global ble_scan_always_enabled 0
  adb shell pm trim-caches 999999G
  ;;
restore)
  for p in "${README_PKGS[@]}" "${EXTRA_PKGS[@]}"; do echo "restore $p: $(adb shell cmd package install-existing --user 0 "$p" 2>&1)"; done
  for p in "${DISABLE_PKGS[@]}"; do echo "enable $p: $(adb shell pm enable --user 0 "$p" 2>&1)"; done
  for s in window_animation_scale transition_animation_scale animator_duration_scale; do adb shell settings put global $s 1.0; done
  ;;
*) echo "usage: $0 [debloat|restore]"; exit 1 ;;
esac
