#include <pipewire/pipewire.h>
#include <spa/buffer/meta.h>
#include <spa/param/video/format-utils.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct state { struct pw_main_loop *loop; struct pw_stream *stream; struct spa_video_info_raw info; FILE *raw; int done; };
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
    printf("{\"width\":%u,\"height\":%u,\"framerateNum\":%u,\"framerateDen\":%u,\"pixelFormatNumeric\":%u,\"strideBytes\":%d,\"bufferBytes\":%u,",
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
static const struct pw_stream_events events = { PW_VERSION_STREAM_EVENTS, .param_changed = param_changed, .process = process };
int main(int argc, char **argv) {
  if (argc != 3) return 2;
  pw_init(&argc, &argv); struct state s = {0};
  s.raw = fopen(argv[2], "wb"); if (!s.raw) return 3; s.loop = pw_main_loop_new(NULL);
  s.stream = pw_stream_new_simple(pw_main_loop_get_loop(s.loop), "spectre-geometry-calibration",
    pw_properties_new(PW_KEY_MEDIA_TYPE, "Video", PW_KEY_MEDIA_CATEGORY, "Capture", PW_KEY_MEDIA_ROLE, "Screen", NULL), &events, &s);
  uint32_t node = (uint32_t)strtoul(argv[1], NULL, 10);
  if (pw_stream_connect(s.stream, PW_DIRECTION_INPUT, node, PW_STREAM_FLAG_AUTOCONNECT | PW_STREAM_FLAG_MAP_BUFFERS, NULL, 0) < 0) return 4;
  pw_main_loop_run(s.loop); fclose(s.raw); pw_stream_destroy(s.stream); pw_main_loop_destroy(s.loop); pw_deinit(); return s.done ? 0 : 5;
}
