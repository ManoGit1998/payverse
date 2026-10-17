{{- define "payverse.fullname" -}}{{ .Release.Name | trunc 40 | trimSuffix "-" }}{{- end -}}

{{- define "payverse.labels" -}}
app.kubernetes.io/part-of: payverse
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{/* in-cluster name of a component: <release>-<name> */}}
{{- define "payverse.svcName" -}}{{ include "payverse.fullname" .root }}-{{ .name }}{{- end -}}

{{- define "payverse.secretName" -}}
{{- if .Values.secrets.existingSecret -}}{{ .Values.secrets.existingSecret }}{{- else -}}{{ include "payverse.fullname" . }}-secrets{{- end -}}
{{- end -}}

{{- define "payverse.image" -}}
{{ .root.Values.image.registry }}/{{ .name }}:{{ .root.Values.image.tag | default .root.Chart.AppVersion }}
{{- end -}}
