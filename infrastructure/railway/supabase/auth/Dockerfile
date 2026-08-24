FROM supabase/gotrue:v2.189.0

EXPOSE 9999

HEALTHCHECK --interval=5s --timeout=5s --retries=10 \
  CMD wget --no-verbose --tries=1 --spider http://127.0.0.1:9999/health
