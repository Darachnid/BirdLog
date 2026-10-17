#include <pebble.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

// Phone -> watch: 1=header, 2=species, 3=sighting/map, 4=saved run, 5=status.
// Watch -> phone: 10=hello, 11=change count, 12=voice transcript, 13=save,
// 14=open, 15=map request, 16=runs request, 17=page request, 18=list request.
enum { KEY_TYPE=0, KEY_INDEX=1, KEY_COUNT=2, KEY_NAME=3, KEY_SCI=4,
       KEY_LAT=5, KEY_LON=6, KEY_TIME=7, KEY_TOTAL=8, KEY_RUN_ID=9,
       KEY_TEXT=10, KEY_PAGE=11, KEY_ACTION=12, KEY_FLAGS=13 };
enum { SCREEN_HOME, SCREEN_LIST, SCREEN_ACTION, SCREEN_COUNT, SCREEN_MAP,
       SCREEN_POINT, SCREEN_RUNS, SCREEN_SAVED, SCREEN_INFO };
#define ROWS 12
#define POINTS 48
#define RUNS 8
#define PER_PAGE 12

typedef struct { char name[42]; char sci[46]; int count; bool present; } Species;
typedef struct { char name[40]; char when[25]; int count; int32_t lat, lon; } Point;
typedef struct { char label[44]; int id; } Run;
static Window *s_window;
static Layer *s_canvas;
static DictationSession *s_dictation;
static Species s_species[ROWS];
static Point s_points[POINTS];
static struct { int32_t lat, lon; } s_track[POINTS];
static int s_num_track;
static Run s_runs[RUNS];
static int s_num_points, s_num_runs, s_total_species, s_selected, s_page,
           s_mode=SCREEN_HOME, s_action, s_edit_number, s_selected_point, s_selected_run;
static int s_current_run;
static bool s_active, s_requested_page;
static char s_status[58]="Connect Android companion";
static char s_title[42]="BirdLog";
#define C_BG GColorFromRGB(248,248,235)
#define C_DARK GColorFromRGB(19,43,47)
#define C_ACCENT GColorFromRGB(0,110,194)

static void redraw(void) { if(s_canvas) layer_mark_dirty(s_canvas); }
static void request(int type, int index, int action, const char *txt) {
  DictionaryIterator *iter;
  if(app_message_outbox_begin(&iter)!=APP_MSG_OK || !iter) return;
  dict_write_uint8(iter, KEY_TYPE, type);
  dict_write_int32(iter, KEY_INDEX, index);
  dict_write_int32(iter, KEY_ACTION, action);
  dict_write_int32(iter, KEY_RUN_ID, s_current_run);
  if(txt) dict_write_cstring(iter, KEY_TEXT, txt);
  dict_write_end(iter);
  app_message_outbox_send();
}
static int valid_rows(void) {
  int n=s_total_species-s_page*PER_PAGE;
  if(n<0) n=0;
  return n>PER_PAGE?PER_PAGE:n;
}
static void clear_rows(void) { memset(s_species,0,sizeof(s_species)); s_requested_page=false; }
static void goto_page(int page) {
  if(page<0 || page*PER_PAGE>=s_total_species) return;
  s_page=page; s_selected=0; clear_rows();
  request(17,s_page,0,NULL); s_requested_page=true; redraw();
}
static void title(GContext *ctx, const char *a, const char *b) {
  graphics_context_set_fill_color(ctx,C_DARK);
  graphics_fill_rect(ctx,GRect(0,0,200,37),0,GCornerNone);
  graphics_context_set_text_color(ctx,GColorWhite);
  graphics_draw_text(ctx,a,fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD),GRect(8,2,184,25),GTextOverflowModeTrailingEllipsis,GTextAlignmentLeft,NULL);
  if(b) graphics_draw_text(ctx,b,fonts_get_system_font(FONT_KEY_GOTHIC_14),GRect(8,20,183,16),GTextOverflowModeTrailingEllipsis,GTextAlignmentRight,NULL);
}
static void footer(GContext *ctx, const char *s) {
  graphics_context_set_fill_color(ctx,C_DARK);
  graphics_fill_rect(ctx,GRect(0,208,200,20),0,GCornerNone);
  graphics_context_set_text_color(ctx,GColorWhite);
  graphics_draw_text(ctx,s,fonts_get_system_font(FONT_KEY_GOTHIC_14),GRect(5,207,190,20),GTextOverflowModeTrailingEllipsis,GTextAlignmentCenter,NULL);
}
static void draw_center(GContext *ctx, int y, const char *msg) {
  graphics_context_set_text_color(ctx,C_DARK);
  graphics_draw_text(ctx,msg,fonts_get_system_font(FONT_KEY_GOTHIC_18),GRect(9,y,182,70),GTextOverflowModeWordWrap,GTextAlignmentCenter,NULL);
}
static void draw_list(GContext *ctx) {
  char buf[30];
  snprintf(buf,sizeof(buf),"%d SPECIES",s_total_species);
  title(ctx,"BIRDLOG",buf);
  int n=valid_rows();
  for(int i=0;i<n && i<5;i++) {
    int idx=(s_selected/5)*5+i;
    if(idx>=n) break;
    Species *b=&s_species[idx]; int y=40+i*33;
    if(idx==s_selected) {
      graphics_context_set_fill_color(ctx,C_ACCENT);
      graphics_fill_rect(ctx,GRect(3,y,194,32),3,GCornersAll);
    } else {
      graphics_context_set_stroke_color(ctx,GColorLightGray);
      graphics_draw_line(ctx,GPoint(6,y+32),GPoint(193,y+32));
    }
    GColor fg=idx==s_selected?GColorWhite:C_DARK;
    graphics_context_set_text_color(ctx,fg);
    snprintf(buf,sizeof(buf),"%d",b->count);
    graphics_draw_text(ctx,b->present?buf:"--",fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD),GRect(6,y+5,31,25),GTextOverflowModeTrailingEllipsis,GTextAlignmentCenter,NULL);
    graphics_context_set_stroke_color(ctx,idx==s_selected?GColorWhite:GColorLightGray);
    graphics_draw_line(ctx,GPoint(40,y+3),GPoint(40,y+29));
    graphics_draw_text(ctx,b->present?b->name:"Loading...",fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD),GRect(45,y,149,23),GTextOverflowModeTrailingEllipsis,GTextAlignmentLeft,NULL);
    if(b->present) graphics_draw_text(ctx,b->sci,fonts_get_system_font(FONT_KEY_GOTHIC_14),GRect(45,y+18,147,17),GTextOverflowModeTrailingEllipsis,GTextAlignmentLeft,NULL);
  }
  snprintf(buf,sizeof(buf),"%d-%d / %d",s_page*PER_PAGE+1,s_page*PER_PAGE+n,s_total_species);
  footer(ctx,buf);
}
static int plot_x(int32_t lon, int32_t min, int32_t max) { return 10+((int64_t)(lon-min)*180)/(max==min?1:(max-min)); }
static int plot_y(int32_t lat, int32_t min, int32_t max) { return 195-((int64_t)(lat-min)*148)/(max==min?1:(max-min)); }
static void draw_map(GContext *ctx) {
  char b[30]; snprintf(b,sizeof(b),"%d SIGHTINGS",s_num_points);
  title(ctx,"SIGHTING MAP",b);
  graphics_context_set_stroke_color(ctx, GColorLightGray);
  for(int x=10;x<200;x+=20) graphics_draw_line(ctx,GPoint(x,43),GPoint(x,201));
  for(int y=44;y<205;y+=20) graphics_draw_line(ctx,GPoint(7,y),GPoint(194,y));
  if(s_num_points==0 && s_num_track==0){ draw_center(ctx,89,"No geotagged birds yet"); footer(ctx,"BACK: checklist"); return; }
  int32_t minlat=s_num_points?s_points[0].lat:s_track[0].lat;
  int32_t minlon=s_num_points?s_points[0].lon:s_track[0].lon;
  int32_t maxlat=minlat,maxlon=minlon;
  for(int i=0;i<s_num_points;i++){
    if(s_points[i].lat<minlat)minlat=s_points[i].lat;
    if(s_points[i].lat>maxlat)maxlat=s_points[i].lat;
    if(s_points[i].lon<minlon)minlon=s_points[i].lon;
    if(s_points[i].lon>maxlon)maxlon=s_points[i].lon;
  }
  // Avoid degenerate map when all sightings share the same phone position.
  for(int i=0;i<s_num_track;i++){
    if(s_track[i].lat<minlat)minlat=s_track[i].lat;
    if(s_track[i].lat>maxlat)maxlat=s_track[i].lat;
    if(s_track[i].lon<minlon)minlon=s_track[i].lon;
    if(s_track[i].lon>maxlon)maxlon=s_track[i].lon;
  }
  if(maxlat-minlat<400){ minlat-=200;maxlat+=200; }
  if(maxlon-minlon<400){ minlon-=200;maxlon+=200; }
  graphics_context_set_stroke_color(ctx,GColorFromRGB(126,157,161));
  for(int i=1;i<s_num_track;i++){
    graphics_draw_line(ctx,
      GPoint(plot_x(s_track[i-1].lon,minlon,maxlon),plot_y(s_track[i-1].lat,minlat,maxlat)),
      GPoint(plot_x(s_track[i].lon,minlon,maxlon),plot_y(s_track[i].lat,minlat,maxlat)));
  }
  for(int i=0;i<s_num_points;i++) {
    int x=plot_x(s_points[i].lon,minlon,maxlon);
    int y=plot_y(s_points[i].lat,minlat,maxlat);
    graphics_context_set_fill_color(ctx,C_ACCENT);
    graphics_fill_circle(ctx,GPoint(x,y),i==s_selected_point?6:3);
    if(i==s_selected_point){graphics_context_set_stroke_color(ctx,C_DARK);graphics_draw_circle(ctx,GPoint(x,y),8);}
  }
  footer(ctx,"UP/DOWN: pin  SELECT: info");
}
static void paint(Layer *layer,GContext *ctx) {
  graphics_context_set_fill_color(ctx,C_BG);graphics_fill_rect(ctx,GRect(0,0,200,228),0,GCornerNone);
  char b[48];
  switch(s_mode) {
    case SCREEN_HOME:
      title(ctx,"BIRDLOG","FIELD NOTEBOOK");
      draw_center(ctx,51,"START NEW RUN");
      draw_center(ctx,90,"OPEN SAVED RUN");
      draw_center(ctx,129,"BIRD CHECKLIST");
      graphics_context_set_stroke_color(ctx,C_ACCENT);
      graphics_draw_round_rect(ctx,GRect(10,48+s_selected*39,180,39),4);
      footer(ctx,"SELECT: open  UP/DOWN: move");break;
    case SCREEN_LIST: draw_list(ctx);break;
    case SCREEN_ACTION:
      title(ctx,"SIGHTING ACTION",s_species[s_selected].name);
      for(int i=0;i<3;i++){
        graphics_context_set_fill_color(ctx,i==s_action?C_ACCENT:C_BG);
        graphics_fill_rect(ctx,GRect(8,49+i*42,184,37),2,GCornersAll);
        graphics_context_set_text_color(ctx,i==s_action?GColorWhite:C_DARK);
        graphics_draw_text(ctx,i==0?"Add +1":(i==1?"Edit total":"Delete sightings"),fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD),GRect(18,55+i*42,160,27),GTextOverflowModeFill,GTextAlignmentLeft,NULL);
      }footer(ctx,"SELECT confirm / BACK cancel");break;
    case SCREEN_COUNT:
      title(ctx,"EDIT TOTAL",s_species[s_selected].name);
      snprintf(b,sizeof(b),"%d",s_edit_number);draw_center(ctx,76,b);
      draw_center(ctx,129,"UP +1    DOWN -1");
      footer(ctx,"SELECT: save count");break;
    case SCREEN_MAP: draw_map(ctx);break;
    case SCREEN_POINT:
      title(ctx,"SIGHTING","");
      draw_center(ctx,48,s_points[s_selected_point].name);
      snprintf(b,sizeof(b),"%d bird(s)",s_points[s_selected_point].count);draw_center(ctx,105,b);
      draw_center(ctx,150,s_points[s_selected_point].when);
      footer(ctx,"BACK: map");break;
    case SCREEN_RUNS:
      title(ctx,"SAVED RUNS","");
      for(int i=0;i<s_num_runs;i++){
        int y=42+i*20;
        graphics_context_set_fill_color(ctx,i==s_selected_run?C_ACCENT:C_BG);
        graphics_fill_rect(ctx,GRect(4,y,190,20),0,GCornerNone);
        graphics_context_set_text_color(ctx,i==s_selected_run?GColorWhite:C_DARK);
        graphics_draw_text(ctx,s_runs[i].label,fonts_get_system_font(FONT_KEY_GOTHIC_14),GRect(8,y,183,18),GTextOverflowModeTrailingEllipsis,GTextAlignmentLeft,NULL);
      }
      if(s_num_runs==0)draw_center(ctx,82,"No saved outings");
      footer(ctx,"SELECT: resume outing");break;
    case SCREEN_SAVED:
      title(ctx,"RUN SAVED","");draw_center(ctx,85,"Saving to phone...");
      draw_center(ctx,129,"Return to home");footer(ctx,"SELECT: home");break;
    default:
      title(ctx,"BIRDLOG","");draw_center(ctx,80,s_status);footer(ctx,"BACK: return");break;
  }
}
static void start_voice(void){
  if(s_dictation){dictation_session_start(s_dictation);}
  else {snprintf(s_status,sizeof(s_status),"Dictation unavailable. Check phone connection.");s_mode=SCREEN_INFO;redraw();}
}
static void dictation_cb(DictationSession *session,DictationSessionStatus status,char *transcription,void *ctx){
  if(status==DictationSessionStatusSuccess && transcription){
    request(12,0,0,transcription);
    snprintf(s_status,sizeof(s_status),"Processing: %.40s",transcription);
    vibes_short_pulse();
  } else snprintf(s_status,sizeof(s_status),"Speech cancelled / unavailable");
  if(s_mode==SCREEN_INFO) s_mode=SCREEN_LIST;
  redraw();
}
static void click_up(ClickRecognizerRef ref,void *ctx){
  if(s_mode==SCREEN_HOME){if(s_selected>0)s_selected--;}
  else if(s_mode==SCREEN_LIST){if(s_selected>0)s_selected--;else goto_page(s_page-1);}
  else if(s_mode==SCREEN_ACTION){if(s_action>0)s_action--;}
  else if(s_mode==SCREEN_COUNT){s_edit_number++;}
  else if(s_mode==SCREEN_MAP){if(s_selected_point>0)s_selected_point--;}
  else if(s_mode==SCREEN_RUNS){if(s_selected_run>0)s_selected_run--;}
  redraw();
}
static void click_down(ClickRecognizerRef ref,void *ctx){
  if(s_mode==SCREEN_HOME){if(s_selected<2)s_selected++;}
  else if(s_mode==SCREEN_LIST){if(s_selected+1<valid_rows())s_selected++;else goto_page(s_page+1);}
  else if(s_mode==SCREEN_ACTION){if(s_action<2)s_action++;}
  else if(s_mode==SCREEN_COUNT){if(s_edit_number>0)s_edit_number--;}
  else if(s_mode==SCREEN_MAP){if(s_selected_point+1<s_num_points)s_selected_point++;}
  else if(s_mode==SCREEN_RUNS){if(s_selected_run+1<s_num_runs)s_selected_run++;}
  redraw();
}
static void click_select(ClickRecognizerRef ref,void *ctx){
  int idx=s_page*PER_PAGE+s_selected;
  switch(s_mode){
    case SCREEN_HOME:
      if(s_selected==0){s_active=true;request(18,-1,1,NULL);s_mode=SCREEN_LIST;goto_page(0);}
      else if(s_selected==1){s_mode=SCREEN_RUNS;s_num_runs=0;s_selected_run=0;request(16,0,0,NULL);}
      else {s_mode=SCREEN_LIST;request(18,0,0,NULL);goto_page(0);}break;
    case SCREEN_LIST:
      if(s_species[s_selected].present){s_action=0;s_mode=SCREEN_ACTION;}break;
    case SCREEN_ACTION:
      if(s_action==0)request(11,idx,1,NULL);
      else if(s_action==2)request(11,idx,-1,NULL);
      else {s_edit_number=s_species[s_selected].count;s_mode=SCREEN_COUNT;break;}
      s_mode=SCREEN_LIST;break;
    case SCREEN_COUNT:
      request(11,idx,s_edit_number+10000,NULL);s_mode=SCREEN_LIST;break;
    case SCREEN_MAP: if(s_num_points>0)s_mode=SCREEN_POINT;break;
    case SCREEN_RUNS:
      if(s_num_runs>0){s_current_run=s_runs[s_selected_run].id;request(14,s_current_run,0,NULL);s_active=true;s_mode=SCREEN_LIST;goto_page(0);}break;
    case SCREEN_SAVED: s_mode=SCREEN_HOME;s_selected=0;break;
    default: s_mode=SCREEN_LIST;break;
  }
  redraw();
}
static void long_up(ClickRecognizerRef ref,void *ctx){start_voice();}
static void long_down(ClickRecognizerRef ref,void *ctx){s_mode=SCREEN_MAP;s_selected_point=0;s_num_points=0;s_num_track=0;request(15,0,0,NULL);redraw();}
static void long_select(ClickRecognizerRef ref,void *ctx){request(13,0,0,NULL);s_active=false;s_mode=SCREEN_SAVED;vibes_short_pulse();redraw();}
static void back_handler(ClickRecognizerRef ref,void *ctx){
  if(s_mode==SCREEN_POINT)s_mode=SCREEN_MAP;
  else if(s_mode==SCREEN_ACTION||s_mode==SCREEN_COUNT||s_mode==SCREEN_MAP||s_mode==SCREEN_INFO)s_mode=SCREEN_LIST;
  else s_mode=SCREEN_HOME;
  redraw();
}
#if defined(PBL_TOUCH)
static void tap_handler(const TouchEvent *event,void *ctx){
  if(s_mode!=SCREEN_MAP||event->type!=TouchEvent_Liftoff||s_num_points<=0)return;
  // Tap the closest plotted blue sighting.
  int32_t minlat=s_points[0].lat,maxlat=minlat,minlon=s_points[0].lon,maxlon=minlon;
  for(int i=1;i<s_num_points;i++){
    if(s_points[i].lat<minlat)minlat=s_points[i].lat;
    if(s_points[i].lat>maxlat)maxlat=s_points[i].lat;
    if(s_points[i].lon<minlon)minlon=s_points[i].lon;
    if(s_points[i].lon>maxlon)maxlon=s_points[i].lon;
  }
  for(int i=0;i<s_num_track;i++){
    if(s_track[i].lat<minlat)minlat=s_track[i].lat;
    if(s_track[i].lat>maxlat)maxlat=s_track[i].lat;
    if(s_track[i].lon<minlon)minlon=s_track[i].lon;
    if(s_track[i].lon>maxlon)maxlon=s_track[i].lon;
  }
  if(maxlat-minlat<400){minlat-=200;maxlat+=200;}
  if(maxlon-minlon<400){minlon-=200;maxlon+=200;}
  int best=0;long bestd=2147483647;
  for(int i=0;i<s_num_points;i++){
    long dx=event->x-plot_x(s_points[i].lon,minlon,maxlon);
    long dy=event->y-plot_y(s_points[i].lat,minlat,maxlat);
    long d=dx*dx+dy*dy;
    if(d<bestd){bestd=d;best=i;}
  }
  if(bestd<420){s_selected_point=best;s_mode=SCREEN_POINT;redraw();}
}
#endif
static void configure(void *ctx){
  window_single_click_subscribe(BUTTON_ID_UP,click_up);
  window_single_click_subscribe(BUTTON_ID_DOWN,click_down);
  window_single_click_subscribe(BUTTON_ID_SELECT,click_select);
  window_single_click_subscribe(BUTTON_ID_BACK,back_handler);
  window_long_click_subscribe(BUTTON_ID_UP,650,long_up,NULL);
  window_long_click_subscribe(BUTTON_ID_DOWN,650,long_down,NULL);
  window_long_click_subscribe(BUTTON_ID_SELECT,950,long_select,NULL);
}
static int value(Tuple *t,int fallback){
  if(!t)return fallback;
  if(t->type==TUPLE_UINT){
    if(t->length==1)return (int)t->value->uint8;
    if(t->length==2)return (int)t->value->uint16;
    return (int)t->value->uint32;
  }
  if(t->type==TUPLE_INT){
    if(t->length==1)return (int)t->value->int8;
    if(t->length==2)return (int)t->value->int16;
    return (int)t->value->int32;
  }
  return fallback;
}
static char *string(Tuple *t){return t?t->value->cstring:NULL;}
static void recv(DictionaryIterator *it,void *ctx){
  int type=value(dict_find(it,KEY_TYPE),-1),i=value(dict_find(it,KEY_INDEX),-1);
  char *name=string(dict_find(it,KEY_NAME)),*sci=string(dict_find(it,KEY_SCI));
  switch(type){
    case 1:
      s_total_species=value(dict_find(it,KEY_TOTAL),s_total_species);
      s_current_run=value(dict_find(it,KEY_RUN_ID),s_current_run);
      if(name)snprintf(s_title,sizeof(s_title),"%s",name);
      break;
    case 2:
      if(i>=0 && i<ROWS){
        if(name)snprintf(s_species[i].name,sizeof(s_species[i].name),"%s",name);
        if(sci)snprintf(s_species[i].sci,sizeof(s_species[i].sci),"%s",sci);
        s_species[i].count=value(dict_find(it,KEY_COUNT),0);
        s_species[i].present=true;
      }break;
    case 3:
      if(i>=0 && i<POINTS){
        Point *p=&s_points[i];
        if(name)snprintf(p->name,sizeof(p->name),"%s",name);
        char *time=string(dict_find(it,KEY_TIME));if(time)snprintf(p->when,sizeof(p->when),"%s",time);
        p->count=value(dict_find(it,KEY_COUNT),0);
        p->lat=value(dict_find(it,KEY_LAT),0);p->lon=value(dict_find(it,KEY_LON),0);
        if(i>=s_num_points)s_num_points=i+1;
      }break;
    case 4:
      if(i>=0 && i<RUNS){
        if(name)snprintf(s_runs[i].label,sizeof(s_runs[i].label),"%s",name);
        s_runs[i].id=value(dict_find(it,KEY_RUN_ID),0);
        if(i>=s_num_runs)s_num_runs=i+1;
      }break;
    case 5:
      if(name)snprintf(s_status,sizeof(s_status),"%s",name);
      if(value(dict_find(it,KEY_FLAGS),0)==1)vibes_short_pulse();
      if(s_mode==SCREEN_SAVED && name && strstr(name,"Saved")!=NULL) window_stack_pop_all(true);
      break;
    case 6:
      if(i>=0 && i<POINTS){
        s_track[i].lat=value(dict_find(it,KEY_LAT),0);
        s_track[i].lon=value(dict_find(it,KEY_LON),0);
        if(i>=s_num_track)s_num_track=i+1;
      }break;
  }
  redraw();
}
static void ready(DictionaryIterator *it,void *ctx){}
static void window_load(Window *window){
  Layer *root=window_get_root_layer(window);
  s_canvas=layer_create(GRect(0,0,200,228));
  layer_set_update_proc(s_canvas,paint);layer_add_child(root,s_canvas);
#if defined(PBL_TOUCH)
  if(touch_service_is_enabled())touch_service_subscribe(tap_handler,NULL);
#endif
}
static void window_unload(Window *window){
#if defined(PBL_TOUCH)
  touch_service_unsubscribe();
#endif
  layer_destroy(s_canvas);s_canvas=NULL;
}
static void init(void){
  s_window=window_create();window_set_window_handlers(s_window,(WindowHandlers){.load=window_load,.unload=window_unload});
  window_set_click_config_provider(s_window,configure);
  window_stack_push(s_window,true);
  app_message_register_inbox_received(recv);app_message_register_outbox_sent(ready);
  app_message_open(512,256);
  s_dictation=dictation_session_create(220,dictation_cb,NULL);
  if(s_dictation)dictation_session_enable_confirmation(s_dictation,true);
  request(10,0,0,NULL);
}
static void deinit(void){if(s_dictation)dictation_session_destroy(s_dictation);window_destroy(s_window);}
int main(void){init();app_event_loop();deinit();}
