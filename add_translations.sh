#!/bin/bash
set -euo pipefail

BASE="app/src/main/res"
MARKER="Haven't found some strings"

# Translation function: inserts block before the closing comment marker
inject() {
  local lang="$1"
  shift
  local file="$BASE/values-$lang/strings.xml"
  if [ ! -f "$file" ]; then
    echo "SKIP: $file not found"
    return
  fi
  # Check if already translated
  if grep -q "notification_actions_label" "$file" 2>/dev/null; then
    echo "SKIP: $lang already has translations"
    return
  fi
  
  # Build the block
  local block=""
  while [ $# -gt 0 ]; do
    block="${block}    $1
"
    shift
  done
  
  # Insert before the marker comment
  local tmp="${file}.tmp"
  awk -v block="$block" -v marker="$MARKER" '{
    if (index($0, marker) > 0) {
      printf "%s", block
    }
    print
  }' "$file" > "$tmp"
  mv "$tmp" "$file"
  echo "OK: $lang"
}

cd "$(dirname "$0")"

# Arabic (ar)
inject ar \
  '<!-- Call Summary -->' \
  '<string name="call_summary_number">الرقم: %s</string>' \
  '<string name="call_summary_recorded">مسجلة: %s</string>' \
  '<string name="call_summary_channel_name">ملخصات المكالمات</string>' \
  '<string name="call_summary_channel_description">إشعارات بملخص المكالمة بعد كل مكالمة</string>' \
  '' \
  '<!-- Notification Actions -->' \
  '<string name="notification_actions_label">إجراءات إشعار نهاية المكالمة</string>' \
  '<string name="notif_action_play_recording">تشغيل التسجيل</string>' \
  '<string name="notif_action_share">مشاركة…</string>' \
  '<string name="notif_action_share_recording">مشاركة التسجيل</string>' \
  '<string name="notif_action_share_transcription">مشاركة النص</string>' \
  '<string name="notif_action_show_transcription">عرض النص</string>' \
  '<string name="share_recording">مشاركة التسجيل</string>' \
  '<string name="recording_not_found">التسجيل غير موجود</string>' \
  '<string name="no_app_to_play_recording">لم يتم العثور على تطبيق لتشغيل التسجيل</string>' \
  '<string name="share_failed">تعذرت المشاركة</string>' \
  '<string name="transcription_not_available">النص غير متاح بعد</string>' \
  '' \
  '<!-- Listen In -->' \
  '<string name="listen_in">استمع</string>' \
  '<string name="listen_in_mode">الاستماع للمكالمات المُجابة تلقائياً</string>' \
  '<string name="listen_in_off">إيقاف</string>' \
  '<string name="listen_in_notification">زر الإشعار</string>' \
  '<string name="listen_in_auto">تلقائي (مكبر الصوت)</string>' \
  '<string name="stop_listening">إيقاف الاستماع</string>' \
  '<string name="hang_up">إنهاء</string>' \
  '<string name="active_call_channel_name">مكالمة نشطة</string>' \
  '<string name="active_call_channel_description">عناصر التحكم للمكالمات المُجابة تلقائياً</string>' \
  '<string name="active_call_notification_title">مُجابة تلقائياً: %s</string>' \
  '<string name="listening_in">مكبر الصوت مفعّل — الاستماع للمكالمة</string>' \
  '<string name="tap_listen_in">اضغط استمع لسماع المكالمة</string>' \
  '' \
  '<!-- Simulate Call -->' \
  '<string name="testing">اختبار</string>' \
  '<string name="simulate_call">محاكاة مكالمة واردة</string>' \
  '<string name="simulate_call_summary">اختبر تجربة المتصل بمكالمة محاكاة</string>' \
  '<string name="simulated_call_banner">⚡ مكالمة محاكاة</string>' \
  '<string name="simulated_call_caller">متصل تجريبي</string>' \
  '<string name="simulated_call_number">+1 555-0100</string>' \
  '<string name="simulated_call_ringing">يرن…</string>' \
  '<string name="simulated_call_active">مكالمة نشطة</string>' \
  '<string name="simulated_call_ended">انتهت المكالمة</string>' \
  '<string name="simulated_call_auto_answering">الرد التلقائي خلال %dث…</string>' \
  '<string name="answer">رد</string>' \
  ''

# Azerbaijani (az)
inject az \
  '<!-- Call Summary -->' \
  '<string name="call_summary_number">Nömrə: %s</string>' \
  '<string name="call_summary_recorded">Qeydə alınıb: %s</string>' \
  '<string name="call_summary_channel_name">Zəng xülasələri</string>' \
  '<string name="call_summary_channel_description">Hər zəngdən sonra xülasə bildirişləri</string>' \
  '' \
  '<!-- Notification Actions -->' \
  '<string name="notification_actions_label">Zəng sonu bildiriş əməliyyatları</string>' \
  '<string name="notif_action_play_recording">Qeydi oxut</string>' \
  '<string name="notif_action_share">Paylaş…</string>' \
  '<string name="notif_action_share_recording">Qeydi paylaş</string>' \
  '<string name="notif_action_share_transcription">Mətni paylaş</string>' \
  '<string name="notif_action_show_transcription">Mətni göstər</string>' \
  '<string name="share_recording">Qeydi paylaş</string>' \
  '<string name="recording_not_found">Qeyd tapılmadı</string>' \
  '<string name="no_app_to_play_recording">Qeydi oxutmaq üçün proqram tapılmadı</string>' \
  '<string name="share_failed">Paylaşmaq mümkün olmadı</string>' \
  '<string name="transcription_not_available">Mətn hələ hazır deyil</string>' \
  '' \
  '<!-- Listen In -->' \
  '<string name="listen_in">Dinlə</string>' \
  '<string name="listen_in_mode">Avtocavab verilən zəngləri dinlə</string>' \
  '<string name="listen_in_off">Söndür</string>' \
  '<string name="listen_in_notification">Bildiriş düyməsi</string>' \
  '<string name="listen_in_auto">Avtomatik (dinamik açıq)</string>' \
  '<string name="stop_listening">Dinləməyi dayandır</string>' \
  '<string name="hang_up">Bağla</string>' \
  '<string name="active_call_channel_name">Aktiv zəng</string>' \
  '<string name="active_call_channel_description">Avtocavab verilən zənglər üçün idarəetmə</string>' \
  '<string name="active_call_notification_title">Avtocavab: %s</string>' \
  '<string name="listening_in">Dinamik açıq — zəngə qulaq asılır</string>' \
  '<string name="tap_listen_in">Zəngi eşitmək üçün Dinlə basın</string>' \
  '' \
  '<!-- Simulate Call -->' \
  '<string name="testing">Test</string>' \
  '<string name="simulate_call">Gələn zəngi simulyasiya et</string>' \
  '<string name="simulate_call_summary">Simulyasiya edilmiş zənglə zəng edən təcrübəsini test edin</string>' \
  '<string name="simulated_call_banner">⚡ SİMULYASİYA ZƏNG</string>' \
  '<string name="simulated_call_caller">Test zəng edən</string>' \
  '<string name="simulated_call_number">+1 555-0100</string>' \
  '<string name="simulated_call_ringing">Zəng çalır…</string>' \
  '<string name="simulated_call_active">Zəng aktiv</string>' \
  '<string name="simulated_call_ended">Zəng bitdi</string>' \
  '<string name="simulated_call_auto_answering">%d san. sonra avtocavab…</string>' \
  '<string name="answer">Cavab ver</string>' \
  ''

# Belarusian (be)
inject be \
  '<!-- Call Summary -->' \
  '<string name="call_summary_number">Нумар: %s</string>' \
  '<string name="call_summary_recorded">Запісана: %s</string>' \
  '<string name="call_summary_channel_name">Зводкі выклікаў</string>' \
  '<string name="call_summary_channel_description">Апавяшчэнні з зводкай пасля кожнага выкліку</string>' \
  '' \
  '<!-- Notification Actions -->' \
  '<string name="notification_actions_label">Дзеянні апавяшчэння пасля выкліку</string>' \
  '<string name="notif_action_play_recording">Прайграць запіс</string>' \
  '<string name="notif_action_share">Падзяліцца…</string>' \
  '<string name="notif_action_share_recording">Падзяліцца запісам</string>' \
  '<string name="notif_action_share_transcription">Падзяліцца транскрыпцыяй</string>' \
  '<string name="notif_action_show_transcription">Паказаць транскрыпцыю</string>' \
  '<string name="share_recording">Падзяліцца запісам</string>' \
  '<string name="recording_not_found">Запіс не знойдзены</string>' \
  '<string name="no_app_to_play_recording">Не знойдзена праграма для прайгравання запісу</string>' \
  '<string name="share_failed">Не ўдалося падзяліцца</string>' \
  '<string name="transcription_not_available">Транскрыпцыя яшчэ недаступная</string>' \
  '' \
  '<!-- Listen In -->' \
  '<string name="listen_in">Слухаць</string>' \
  '<string name="listen_in_mode">Слухаць аўтаадказаныя выклікі</string>' \
  '<string name="listen_in_off">Выкл</string>' \
  '<string name="listen_in_notification">Кнопка апавяшчэння</string>' \
  '<string name="listen_in_auto">Аўта (дынамік уключаны)</string>' \
  '<string name="stop_listening">Спыніць праслухоўванне</string>' \
  '<string name="hang_up">Скінуць</string>' \
  '<string name="active_call_channel_name">Актыўны выклік</string>' \
  '<string name="active_call_channel_description">Кіраванне аўтаадказанымі выклікамі</string>' \
  '<string name="active_call_notification_title">Аўтаадказ: %s</string>' \
  '<string name="listening_in">Дынамік уключаны — праслухоўванне выкліку</string>' \
  '<string name="tap_listen_in">Націсніце Слухаць каб пачуць выклік</string>' \
  '' \
  '<!-- Simulate Call -->' \
  '<string name="testing">Тэставанне</string>' \
  '<string name="simulate_call">Сімуляцыя ўваходнага выкліку</string>' \
  '<string name="simulate_call_summary">Праверце вопыт абанента з сімуляваным выклікам</string>' \
  '<string name="simulated_call_banner">⚡ СІМУЛЯЦЫЯ ВЫКЛІКУ</string>' \
  '<string name="simulated_call_caller">Тэставы абанент</string>' \
  '<string name="simulated_call_number">+1 555-0100</string>' \
  '<string name="simulated_call_ringing">Званок…</string>' \
  '<string name="simulated_call_active">Выклік актыўны</string>' \
  '<string name="simulated_call_ended">Выклік завершаны</string>' \
  '<string name="simulated_call_auto_answering">Аўтаадказ праз %dс…</string>' \
  '<string name="answer">Адказаць</string>' \
  ''

echo "Done with first batch"
