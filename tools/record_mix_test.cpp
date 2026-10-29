#include "../windows/record_mix.h"
#include <iostream>
#include <stdexcept>
static void require(bool value,const char* message) { if (!value)throw std::runtime_error(message); }
int main() {
    using namespace recording;
    try {
        Mixer mix(100,100);
        const float desktop[]={.3f,-.2f,.4f,-.3f};
        const float mic[]={.1f,.1f,.2f,.2f};
        mix.system.put(0,desktop,2,mix.cursor);mix.microphone.put(0,mic,2,mix.cursor);
        auto out=mix.render(2);
        require(out[0]>13000 && out[0]<13200 && out[1]<-3200 && out[1]>-3400,"two sources retain stereo and mix");
        require(out[2]>19500 && out[3]<-3200,"independent microphone source");
        auto silence=mix.render(10);require(std::all_of(silence.begin(),silence.end(),[](int16_t x){return x==0;}),"idle endpoint produces timed silence");
        Mixer clipping(200,200);const float peak[]={1.f,-1.f};
        clipping.system.put(0,peak,1,0);clipping.microphone.put(0,peak,1,0);
        auto limited=clipping.render(1);require(limited[0]==32767 && limited[1]==-32767,"saturated mixing must not wrap");
        Mixer muted(0,100);muted.system.put(0,desktop,2,0);muted.microphone.put(0,mic,2,0);
        require(muted.render(1)[0]==3277,"gain zero mutes one source without changing other input");
        Mixer delayed(100,100);delayed.system.put(-1,desktop,2,0);
        require(delayed.render(1)[0]>13000,"capture before clock origin is clipped safely");
        delayed.system.put(0,desktop,2,delayed.cursor);
        require(delayed.render(1)[0]>13000,"late packets cannot overwrite committed samples");
        delayed.cursor=Timeline::capacity+1;
        require(delayed.render(1)[0]==0,"ring wrap never replays old audio");
        std::vector<float> invalid={std::numeric_limits<float>::quiet_NaN(),std::numeric_limits<float>::infinity()};
        Mixer finite(100,100);finite.system.put(0,invalid.data(),1,0);require(finite.render(1)[0]==0,"nonfinite driver samples become silence");
        for (unsigned rate:{44100u,48000u,96000u}) {
            uint64_t first=0,count=0;
            while(first<rate*2) {
                auto n=std::min<uint64_t>(147,rate*2-first);std::vector<float> stereo(size_t(n)*2,.25f);
                auto samples=resample(stereo,first,rate);count+=samples.size()/2;
                require(std::all_of(samples.begin(),samples.end(),[](float x){return std::abs(x-.25f)<.00001f;}),"resampling preserves constant amplitude");first+=n;
            }
            require(count==96000,"fractional sample rate conversion never accumulates packet rounding");
        }
        for(int corner=0;corner<4;++corner) for(auto dims:{std::pair<unsigned,unsigned>{320,240},{108,192}}) {
            std::vector<uint8_t> desktop(640*360*4,40),camera(size_t(dims.first)*dims.second*4,180);
            auto box=pictureBox(640,360,dims.first,dims.second,corner);overlay(desktop,640,360,camera,dims.first,dims.second,corner);
            require(box.x+box.width<=640 && box.y+box.height<=360,"picture-in-picture bounds");
            require(std::abs(double(box.width)/box.height-double(dims.first)/dims.second)<.02,"camera aspect ratio");
            require(desktop[(size_t(box.y+box.height/2)*640+box.x+box.width/2)*4]==180,"camera is present in composed frame");
            require(desktop[(size_t(180)*640+320)*4]==40,"desktop outside inset remains visible");
        }
        std::cout<<"mixing, bounded timeline, resampling and panorama composition passed\n";return 0;
    } catch(const std::exception& e) {std::cerr<<e.what()<<"\n";return 1;}
}
