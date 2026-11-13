#!/system/bin/sh
until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 3; done
sleep 15
P=com.fox.audiosplit
pm grant $P android.permission.RECORD_AUDIO
pm grant $P android.permission.POST_NOTIFICATIONS
am start-foreground-service -n $P/.SplitService
