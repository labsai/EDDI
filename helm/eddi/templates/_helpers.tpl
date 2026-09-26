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
Whether a SECURE-BY-DEFAULT switch is on: "true" or "false".

Absent means ON. `helm upgrade --reuse-values` reuses the previous release's
values and ignores this chart's defaults, so a switch introduced by a newer
chart (mongodb.auth, networkPolicy.datastores) is simply MISSING on such an
upgrade. Read as `(… | default dict).enabled` that absence was "off": the
upgrade exited 0 and left MongoDB unauthenticated with no datastore policy
while looking hardened. Only an explicit false (boolean, or the string
"false") turns one of these off.

Call with (dict "section" <map or nil>).
*/}}
{{- define "eddi.enabledUnlessFalse" -}}
{{- $section := .section -}}
{{- if and (kindIs "map" $section) (hasKey $section "enabled") -}}
{{- $enabled := $section.enabled -}}
{{- if kindIs "bool" $enabled -}}
{{- $enabled -}}
{{- else -}}
{{- /* A string "false" / "FALSE" also turns it off; nil falls to "true" before toString (see the chart's coalesce-first rule). */ -}}
{{- ne (lower (toString (default "true" $enabled))) "false" -}}
{{- end -}}
{{- else -}}
true
{{- end -}}
{{- end }}

{{/* The in-chart MongoDB runs with authentication. See eddi.enabledUnlessFalse. */}}
{{- define "eddi.mongoAuthEnabled" -}}
{{- and .Values.mongodb.enabled (eq (include "eddi.enabledUnlessFalse" (dict "section" .Values.mongodb.auth)) "true") -}}
{{- end }}
