#include <pipewire/pipewire.h>
#include <spa/buffer/meta.h>
#include <spa/param/param.h>
#include <spa/param/format-utils.h>
#include <spa/pod/builder.h>
#include <spa/param/video/format-utils.h>
#include <spa/utils/result.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct state { struct pw_main_loop *loop; struct pw_stream *stream; struct spa_video_info_raw info; FILE *raw; int done; enum pw_stream_state stream_state; char error[256]; };
static const char *safe(const char *value) { return value ? value : ""; }
static void json_string(const char *value) {
  putchar('"');
  for (const unsigned char *p = (const unsigned char *)safe(value); *p; p++) {
    if (*p == '"' || *p == '\\') putchar('\\');
    if (*p >= 0x20) putchar(*p);
  }
  putchar('"');
}
static void state_changed(void *data, enum pw_stream_state old, enum pw_stream_state state,
                          const char *error) {
  struct state *s = data;
  s->stream_state = state;
  snprintf(s->error, sizeof(s->error), "%s", safe(error));
  fprintf(stderr, "PipeWire stream state: %s -> %s%s%s\n", pw_stream_state_as_string(old),
          pw_stream_state_as_string(state), error ? ": " : "", safe(error));
  if (state == PW_STREAM_STATE_ERROR) pw_main_loop_quit(s->loop);
}
static void param_changed(void *data, uint32_t id, const struct spa_pod *param) {
  struct state *s = data;
  if (id == SPA_PARAM_Format && param) spa_format_video_raw_parse(param, &s->info);
}
static void process(void *data) {
  struct state *s = data; struct pw_buffer *pb;
  if (!(pb = pw_stream_dequeue_buffer(s->stream))) return;
  struct spa_buffer *b = pb->buffer; struct spa_data *d = &b->datas[0];
  if (!s->done && d->data && d->chunk && d->chunk->size) {
    fwrite((uint8_t *)d->data + d->chunk->offset, 1, d->chunk->size, s->raw);
    struct spa_meta_region *crop = spa_buffer_find_meta_data(b, SPA_META_VideoCrop, sizeof(*crop));
    struct spa_meta_videotransform *transform = spa_buffer_find_meta_data(b, SPA_META_VideoTransform, sizeof(*transform));
    printf("{\"streamState\":\"%s\",\"width\":%u,\"height\":%u,\"framerateNum\":%u,\"framerateDen\":%u,\"pixelFormatNumeric\":%u,\"strideBytes\":%d,\"bufferBytes\":%u,",
      pw_stream_state_as_string(s->stream_state),
      s->info.size.width, s->info.size.height, s->info.framerate.num, s->info.framerate.denom,
      s->info.format, d->chunk->stride, d->chunk->size);
    if (crop) printf("\"videoCrop\":{\"present\":true,\"left\":%d,\"top\":%d,\"width\":%u,\"height\":%u},", crop->region.position.x, crop->region.position.y, crop->region.size.width, crop->region.size.height);
    else printf("\"videoCrop\":{\"present\":false},");
    if (transform) printf("\"videoTransform\":{\"present\":true,\"numeric\":%u}}\n", transform->transform);
    else printf("\"videoTransform\":{\"present\":false}}\n");
    fflush(stdout); s->done = 1; pw_main_loop_quit(s->loop);
  }
  pw_stream_queue_buffer(s->stream, pb);
}
static const struct pw_stream_events events = { PW_VERSION_STREAM_EVENTS, .state_changed = state_changed, .param_changed = param_changed, .process = process };
int main(int argc, char **argv) {
  if (argc != 3) return 2;
  pw_init(&argc, &argv); struct state s = {0};
  s.raw = fopen(argv[2], "wb"); if (!s.raw) return 3; s.loop = pw_main_loop_new(NULL);
  s.stream = pw_stream_new_simple(pw_main_loop_get_loop(s.loop), "spectre-geometry-calibration",
    pw_properties_new(PW_KEY_MEDIA_TYPE, "Video", PW_KEY_MEDIA_CATEGORY, "Capture", PW_KEY_MEDIA_ROLE, "Screen", NULL), &events, &s);
  uint8_t pod_buffer[2048]; struct spa_pod_builder builder = SPA_POD_BUILDER_INIT(pod_buffer, sizeof(pod_buffer));
  const struct spa_pod *params[3];
  struct spa_rectangle def_size = SPA_RECTANGLE(480, 283), min_size = SPA_RECTANGLE(1, 1), max_size = SPA_RECTANGLE(8192, 8192);
  struct spa_fraction def_rate = SPA_FRACTION(30, 1), min_rate = SPA_FRACTION(0, 1), max_rate = SPA_FRACTION(120, 1);
  params[0] = spa_pod_builder_add_object(&builder, SPA_TYPE_OBJECT_Format, SPA_PARAM_EnumFormat,
    SPA_FORMAT_mediaType, SPA_POD_Id(SPA_MEDIA_TYPE_video),
    SPA_FORMAT_mediaSubtype, SPA_POD_Id(SPA_MEDIA_SUBTYPE_raw),
    SPA_FORMAT_VIDEO_format, SPA_POD_CHOICE_ENUM_Id(6, SPA_VIDEO_FORMAT_RGBA, SPA_VIDEO_FORMAT_RGBA,
      SPA_VIDEO_FORMAT_BGRA, SPA_VIDEO_FORMAT_RGBx, SPA_VIDEO_FORMAT_BGRx, SPA_VIDEO_FORMAT_RGB),
    SPA_FORMAT_VIDEO_size, SPA_POD_CHOICE_RANGE_Rectangle(&def_size, &min_size, &max_size),
    SPA_FORMAT_VIDEO_framerate, SPA_POD_CHOICE_RANGE_Fraction(&def_rate, &min_rate, &max_rate));
  params[1] = spa_pod_builder_add_object(&builder, SPA_TYPE_OBJECT_ParamMeta, SPA_PARAM_Meta,
    SPA_PARAM_META_type, SPA_POD_Id(SPA_META_VideoCrop),
    SPA_PARAM_META_size, SPA_POD_Int((int)sizeof(struct spa_meta_region)));
  params[2] = spa_pod_builder_add_object(&builder, SPA_TYPE_OBJECT_ParamMeta, SPA_PARAM_Meta,
    SPA_PARAM_META_type, SPA_POD_Id(SPA_META_VideoTransform),
    SPA_PARAM_META_size, SPA_POD_Int((int)sizeof(struct spa_meta_videotransform)));
  uint32_t node = (uint32_t)strtoul(argv[1], NULL, 10);
  int connected = pw_stream_connect(s.stream, PW_DIRECTION_INPUT, node,
    PW_STREAM_FLAG_AUTOCONNECT | PW_STREAM_FLAG_MAP_BUFFERS, params, 3);
  if (connected < 0) { fprintf(stderr, "pw_stream_connect: %s\n", spa_strerror(connected)); return 4; }
  pw_main_loop_run(s.loop);
  if (!s.done) {
    printf("{\"streamState\":\"%s\",\"error\":", pw_stream_state_as_string(s.stream_state));
    json_string(s.error[0] ? s.error : "stream ended without a mapped video buffer");
    printf(",\"videoCrop\":{\"present\":false},\"videoTransform\":{\"present\":false}}\n");
  }
  fclose(s.raw); pw_stream_destroy(s.stream); pw_main_loop_destroy(s.loop); pw_deinit(); return s.done ? 0 : 5;
}
