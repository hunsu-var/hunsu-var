#!/usr/bin/env bash
# Inject vehicle signals into the Pleos emulator through the Virtual Console HTTP API (port 8088).
# `adb ... inject-vhal-event` does NOT work (user build); this does (verified on the Pleos Connect 2.1.0 emulator).
#   scripts/vhal.sh speed 60            km/h (persists until changed; VC has no physics)
#   scripts/vhal.sh ramp 60 0 7         smooth speed change from 60 to 0 km/h at 7 km/h per second (= 1.9 m/s²)
#   scripts/vhal.sh gear P|R|N|D
#   scripts/vhal.sh turn L|R|off
#   scripts/vhal.sh steer <deg>         VC +deg arrives in the app as -deg
#   scripts/vhal.sh brake on|off        on = pedal pressed (VC 100), off = released (VC 0)
#   scripts/vhal.sh wiper on|off
#   scripts/vhal.sh lowbeam on|off      (off = Auto; with Auto the lamp reports off in daylight)
#   scripts/vhal.sh seat driver|passenger|rear_left|rear_right occupied|empty   (INVERTED in the emulator, see doc)
#   scripts/vhal.sh telltale <Type> On|Off|Blink [Color]   (Type = VC list; LCA_*/FCA/LKA are NOT injectable)
#   scripts/vhal.sh prep                before a PT/live run: checks car_service, then P, speed 0, steer 0,
#                                       brake/turn/wiper off, all seats "occupied"
#   scripts/vhal.sh state | stop        stop = kill the heartbeat
# The VC only accepts writes while a browser "heartbeat" (POST /browser/session) arrived within 3 s,
# so the first call starts a background heartbeat loop (pid in $PID).
set -euo pipefail
B=http://127.0.0.1:8088
PID=${TMPDIR:-/tmp}/vhal-heartbeat.pid

TAG=vhal-heartbeat   # marker in the heartbeat's argv; stop only kills a pid that still carries it
owned() { case "$(ps -p "$(cat "$PID" 2>/dev/null)" -o command= 2>/dev/null)" in *"$TAG"*) return 0;; *) return 1;; esac; }
heartbeat() {
  if owned; then return; fi
  adb forward tcp:8088 tcp:8088 >/dev/null
  # bounded: exits after 30 min, or after 5 consecutive failed POSTs
  nohup bash -c 'end=$(( $(date +%s) + 1800 )); fails=0
    while [ "$(date +%s)" -lt "$end" ] && [ "$fails" -lt 5 ]; do
      if curl -sf -m 2 -X POST "$1/browser/session?client=vhal-sh" >/dev/null; then fails=0; else fails=$((fails+1)); fi
      sleep 1
    done' "$TAG" "$B" >/dev/null 2>&1 &
  echo $! > "$PID"; sleep 1.5
}
put() { curl -sf -m 8 -X PUT "$B$1" >/dev/null || { echo "FAIL $1" >&2; exit 1; }; echo "ok $1"; }
usage() { echo "usage:"; grep '^#   ' "$0" | sed 's/^# //'; exit 1; }
bad() { echo "$1" >&2; exit 1; }

# Validate and build the request paths first, so a typo never starts the heartbeat.
paths=""
case "${1:-}" in
  stop) if owned; then kill "$(cat "$PID")" || true; fi; rm -f "$PID"; exit 0 ;;
  speed)   case "${2:-}" in ''|*[!0-9]*) bad "speed <km/h, whole number (VC rejects decimals)>";; esac; paths="/vehicle/controls?speed=$2" ;;
  ramp)    for v in "${2:-}" "${3:-}"; do case "$v" in ''|*[!0-9]*) bad "ramp <from> <to> <km/h per s> (from/to whole numbers)";; esac; done
           case "${4:-}" in ''|*[!0-9.]*) bad "ramp <from> <to> <km/h per s>";; esac ;;
  gear)    case "${2:-}" in P) g=Park;; R) g=Reverse;; N) g=Neutral;; D) g=Drive;; *) bad "gear P|R|N|D";; esac
           paths="/vehicle/gear?gear=$g" ;;
  turn)    case "${2:-}" in L) m=BlinkLeft;; R) m=BlinkRight;; off) m=Off;; *) bad "turn L|R|off";; esac
           paths="/vehicle/turn-signal?mode=$m" ;;
  steer)   case "${2:-}" in ''|-|*[!0-9.-]*) bad "steer <deg>";; esac; paths="/vehicle/controls?steering=$2" ;;
  brake)   case "${2:-}" in on) v=100;; off) v=0;; *) bad "brake on|off";; esac   # VC: >0 = pressed
           paths="/vehicle/controls?brake=$v" ;;
  wiper)   case "${2:-}" in on) m=Low;; off) m=Off;; *) bad "wiper on|off";; esac; paths="/vehicle/body?front_wiper=$m" ;;
  lowbeam) case "${2:-}" in on) m=LowBeam;; off) m=Auto;; *) bad "lowbeam on|off";; esac
           paths="/vehicle/lights?feature=headlamp_mode&value=$m" ;;
  seat)    case "${2:-}" in driver|passenger|rear_left|rear_right) ;; *) bad "seat driver|passenger|rear_left|rear_right occupied|empty";; esac
           case "${3:-}" in occupied) v=true;; empty) v=false;; *) bad "seat <area> occupied|empty";; esac
           paths="/vehicle/seat?kind=occupied&area=$2&value=$v" ;;
  telltale) [ -n "${2:-}" ] && [ -n "${3:-}" ] || bad "telltale <Type> On|Off|Blink [Color]"
           paths="/vehicle/telltales/simulator?type=$2&state=$3&color=${4:-Red}" ;;
  prep)    # A long-running emulator can lose car_service (ANR loop): then the app never sees the gear and stays on
           # "기록 중". Only a cold boot fixes it (README §3(c)).
           adb shell service check car_service | grep -q ': found' || bad "car_service not running → cold-boot the emulator (README §3(c))"
           # parked, still, signals off; VC "occupied" on all seats = app sees them EMPTY (inverted) → no belt candidate
           paths="/vehicle/gear?gear=Park /vehicle/controls?speed=0 /vehicle/controls?steering=0 /vehicle/controls?brake=0
                  /vehicle/turn-signal?mode=Off /vehicle/body?front_wiper=Off"
           for a in driver passenger rear_left rear_right; do paths="$paths /vehicle/seat?kind=occupied&area=$a&value=true"; done ;;
  state)   ;;
  *) usage ;;
esac
heartbeat
if [ "$1" = state ]; then curl -sf -m 8 "$B/vehicle/state" || bad "FAIL /vehicle/state"; exit 0; fi
if [ "$1" = ramp ]; then
  # VC speed is a step function and integer-only (km/h). 1 km/h steps that follow the clock keep the app's per-second
  # decel at the target rate (one 7 km/h jump would read as a spike). perl: macOS bash/date have no sub-second clock.
  perl -MTime::HiRes=time,sleep -e '
    my ($from, $to, $rate, $base) = @ARGV; my $dur = abs($to - $from) / $rate; my $t0 = time;
    while (1) {
      my $e = time - $t0; my $v = $e >= $dur ? $to : $from + ($to <=> $from) * $rate * $e;
      system("curl", "-sf", "-m", "2", "-X", "PUT", "$base/vehicle/controls?speed=" . int($v + 0.5)) == 0
        or die "FAIL ramp\n";
      last if $e >= $dur; sleep 0.05;
    }' "$2" "$3" "$4" "$B" >/dev/null
  echo "ok ramp $2 -> $3 km/h at $4 km/h/s"; exit 0
fi
set -f  # paths contain `?`: no globbing
for p in $paths; do put "$p"; done
