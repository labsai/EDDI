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
Service account name
*/}}
{{- define "eddi.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "eddi.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}
