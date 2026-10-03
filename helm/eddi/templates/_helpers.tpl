{{/*
EDDI Helm Chart — Template helpers
*/}}

{{/*
Expand the name of the chart.
*/}}
{{- define "eddi.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "eddi.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
The EDDI image tag: eddi.image.tag when set, otherwise the chart's appVersion.
Chart.yaml is then the one place a release has to move, and the image and the
app.kubernetes.io/version label cannot disagree unless an operator overrides
the tag on purpose.
*/}}
{{- define "eddi.imageTag" -}}
{{- .Values.eddi.image.tag | default .Chart.AppVersion -}}
{{- end }}

{{/*
Common labels.

helm.sh/chart carries the chart NAME AND VERSION. It used to render just the
name, which made the label the constant string "eddi" on every release of every
chart version — so `kubectl get all -l helm.sh/chart=eddi-1.0.1`, the standard
way to ask a live cluster which chart revision produced an object, matched
nothing.
*/}}
{{- define "eddi.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: eddi
{{ include "eddi.selectorLabels" . }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "eddi.selectorLabels" -}}
app.kubernetes.io/name: {{ include "eddi.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
The messaging coordinator.

Coalesced to the values.yaml default and lowercased ONCE, so the guard in
configmap.yaml, the EDDI_MESSAGING_TYPE it renders and the line NOTES.txt prints
cannot disagree about what a value means. They did: the guard read
`default "in-memory" .Values.eddi.messagingType` while both renders read the raw
value, so a values file carrying a bare `messagingType:` — a YAML null — passed
the guard as "in-memory" and then rendered `EDDI_MESSAGING_TYPE:` with nothing
after it (Sprig's `quote` skips a nil) under an install note reading
"Messaging: ".

`default` runs BEFORE toString, as everywhere else in this chart: `toString nil`
is fmt.Sprintf("%v", nil), the literal, non-empty, TRUTHY string "<nil>".
*/}}
{{- define "eddi.messagingType" -}}
{{- lower (toString (default "in-memory" .Values.eddi.messagingType)) }}
{{- end }}

{{/*
Service account name
*/}}
{{- define "eddi.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "eddi.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Deployment update strategy type: eddi.updateStrategy, or by messaging type when
it is empty or null — Recreate for in-memory (one JVM against the database at a
time), RollingUpdate for nats (replicas coordinate through NATS). The value is
either a plain string ("Recreate" / "RollingUpdate") or the Deployment's own
strategy object ({type: ..., rollingUpdate: {...}}), so a values file written
for either shape renders.
*/}}
{{- define "eddi.updateStrategy" -}}
{{- $value := .Values.eddi.updateStrategy }}
{{- $configured := "" }}
{{- if kindIs "map" $value }}{{ $configured = toString (default "" $value.type) }}{{ else }}{{ $configured = toString (default "" $value) }}{{ end }}
{{- if $configured }}{{ $configured }}{{ else if eq (include "eddi.messagingType" .) "nats" }}RollingUpdate{{ else }}Recreate{{ end }}
{{- end }}

{{/*
Termination grace: eddi.terminationGracePeriodSeconds, else 75 s in cluster
mode (drain 65 s + readiness grace + margin) and 30 s otherwise.
*/}}
{{- define "eddi.terminationGracePeriodSeconds" -}}
{{- $configured := toString (default "" .Values.eddi.terminationGracePeriodSeconds) }}
{{- if $configured }}{{ $configured }}{{ else if eq (include "eddi.messagingType" .) "nats" }}75{{ else }}30{{ end }}
{{- end }}

{{/*
Shutdown drain timeout: eddi.shutdownDrainTimeoutSeconds, else 65 s in cluster
mode; empty (the application default) otherwise.
*/}}
{{- define "eddi.shutdownDrainTimeoutSeconds" -}}
{{- $configured := toString (default "" .Values.eddi.shutdownDrainTimeoutSeconds) }}
{{- if $configured }}{{ $configured }}{{ else if eq (include "eddi.messagingType" .) "nats" }}65{{ end }}
{{- end }}

{{/*
NATS servers for EDDI: the in-chart StatefulSet's pods (all of them, so the
client fails over without DNS) or nats.externalUrl.
*/}}
{{- define "eddi.natsUrl" -}}
{{- if .Values.nats.enabled -}}
{{- $fullname := include "eddi.fullname" . -}}
{{- $urls := list -}}
{{- range $i := until (int .Values.nats.replicas) -}}
{{- $urls = append $urls (printf "nats://%s-nats-%d.%s-nats:4222" $fullname $i $fullname) -}}
{{- end -}}
{{- join "," $urls -}}
{{- else -}}
{{- .Values.nats.externalUrl -}}
{{- end -}}
{{- end }}
