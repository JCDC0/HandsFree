# HandsFree Manuscript — Full Extract

## Page 1

​
              ​
 ​
1   
 
HOLY ANGEL UNIVERSITY 
HandsFree: An Offline Hands-Free Android App Using Voice Commands in Filipino, 
English, and Taglish 
 
A Thesis Project 
Presented to the Faculty of the  
School of Computing 
Holy Angel University 
 
 
 
In Partial Fulfillment 
of the Requirements for the Degree 
Bachelor of Science in Computer Science 
​
 
 
 
                                   Eriel Ben Baguio​            John Harold Doton 
                                   Juan Carlo De Castro     Karl Shane Bernarte 
 
 
 
 
March, 2026 
 
 
 
 
 
SCHOOL OF COMPUTING

## Page 2

​
              ​
 ​
2   
 
HOLY ANGEL UNIVERSITY 
Table of Contents 
        Page 
Cover Page​
i 
Table of Contents​
ii 
 
Introduction​
3-5 
Review of Related Literature​
5-13 
Conceptual Framework​
14-19 
Objectives of the Study​
19-21 
Scope and Delimitations​
21-23 
 
Methods​
23 
Research Design​
23-24 
Sources of Data​
24 
Participants​
24-25 
Instruments​
25-26 
Data Collection​
26-27 
Data Analysis​
27-28 
Research Procedure​
28-36 
Ethical Considerations ​
36-37 
References​
37-43 
 
Appendices 
 
Appendix A   RAD Model Lifecycle​
​
44 
Appendix B   CRISP-DM Lifecycle​
​
45 
Appendix C   Proposed Architectural Design of HandsFree​
46-47 
Appendix D   End-User Survey Instrument (Google Forms)​
47-48 
Appendix E   Expert Evaluator Survey Instrument (Google Forms)​
49-50 
 
List of Figures 
​
 
Figure 1  IPO Diagram​
​
12 
Figure 2  The Rapid Application Development (RAD) Model Lifecycle​
22 
Figure 3​  The Cross-Industry Standard Process for Data Mining (CRISP-DM) Lifecycle​
23​
Figure 4  Proposed Architectural Design of HandsFree​
24 
 
 
 
 
 
 
 
 
SCHOOL OF COMPUTING

## Page 3

​
              ​
 ​
3   
 
HOLY ANGEL UNIVERSITY 
HandsFree: An Offline Hands-Free Android App Using Voice Commands in Filipino, 
English, and Taglish 
Introduction 
The growing prevalence of mobile devices as primary tools for communication and 
daily life management has brought the limitations of existing voice navigation architectures 
into sharper focus. For individuals who cannot easily use a touchscreen, such as those with 
visual impairments or limited hand mobility, voice control is not simply a convenience but a 
necessity. Yet voice assistants available today fall short of fixing this problem. Most popular 
assistants, such as Google Assistant and Siri, send every spoken command to distant servers 
over the internet to process it, which means they require a stable connection, have possible 
delays, and continuously transmit private audio data to third-party infrastructure (Pan et al., 
2025; Torkamani & Zarin, 2025). Running the same processing directly on a phone avoids 
these problems in principle, but common smartphones lack the computer power to run the 
large AI models these assistants depend on (Wang et al., 2024). Taken together, these factors 
leave users who require consistent, always-available hands-free navigation, particularly those 
with visual impairments or motor limitations, without a dependable solution. 
Existing hands-free navigation systems fall into two architectural categories, each 
carrying distinct operational limitations. Traditional voice control mechanisms require 
explicit API integration from individual application developers, which restricts their utility to 
pre-configured software and excludes the vast majority of third-party Android applications. 
More recent GUI agents, such as Mobile-Agent-v3 (Ye et al., 2025) and V-Droid (Dai et al., 
2025), deploy Large Language Models and Vision-Language Models to automate screen 
 
 
 
 
SCHOOL OF COMPUTING

## Page 4

​
              ​
 ​
4   
 
HOLY ANGEL UNIVERSITY 
interaction through probabilistic visual inference. While these systems achieve high 
benchmark accuracy under controlled conditions, Dong et al. (2025) identified a persistent 
'reasoning-execution gap' in which models correctly interpret user intent but consistently fail 
to translate that reasoning into accurate, deterministic physical touch coordinates. The result 
is hallucinated UI interactions—incorrect or phantom gestures—that are fundamentally 
incompatible with the reliability demands of assistive technology. Systems such as Voicify 
(Vu et al., 2023) partially address universal navigation by leveraging Android accessibility 
metadata, but their reliance on deep learning parsers introduces inference delays that conflict 
with strict offline and privacy-first requirements. Across both categories, existing solutions 
prioritize capability over execution determinism, leaving individuals with visual or motor 
impairments without a reliably applicable navigation tool. 
The proposed HandsFree system addresses these limitations through a fully offline 
Speech-to-Intent architecture integrated with the native Android Accessibility Service API. 
Rather than generating probabilistic text responses or relying on visual inference, the system 
extracts structured metadata directly from the active UI accessibility node tree, bypassing the 
interpretation errors common in generative models (Xie et al., 2021). A spoken command is 
converted to text on the phone itself, matched against a set list of recognized actions, and 
then the correct button is tapped automatically. The system can cover everyday task areas 
such as launching apps, performing searches, sending messages, setting schedules, and 
accepting incoming calls. Because every step happens locally on the device, no audio is sent 
to a server, and the system would work even without internet. The primary beneficiaries of 
this study are individuals with visual impairments or physical motor limitations who require 
 
 
 
 
SCHOOL OF COMPUTING

## Page 5

​
              ​
 ​
5   
 
HOLY ANGEL UNIVERSITY 
an independent, universally applicable, and privacy-preserving mobile navigation mechanism 
that functions reliably across all Android applications without requiring cooperation from 
third-party developers. 
HandsFree makes use of its unique built-in language bridge. In everyday Filipino 
speech, it is natural to mix Tagalog and English in the same sentence, which is a style 
commonly called Taglish. Voice assistants like Google Assistant and Siri were not designed 
for this, and would fail when a user switches between languages mid-command (Mark Louis 
Lim et al., 2022). HandsFree allows this by running a translation step before anything else: 
words and phrases in Tagalog or Taglish are automatically converted to their English 
equivalents, so that commands like "i-open ang Settings" and "buksan ang Settings" are 
treated the same as "open the Settings." This step is handled through a fixed word list rather 
than a large AI model, keeping the system fast and lightweight on standard phone hardware. 
Review of Related Literature 
Privacy Risks and Latency in Cloud-Based AI Assistants 
The integration of highly autonomous, cloud-based AI models into daily mobile tasks 
has introduced unprecedented data security vulnerabilities and performance bottlenecks. Pan 
et al. (2025) highlight that task-executable voice assistants constantly invoke system 
applications, risking privilege escalation and severe privacy misdisclosures during inter-app 
interactions without explicit user consent. Exacerbating this, Shi et al. (2025) point out that 
as GUI agents gain autonomy, their reliance on dynamic remote environments and 
action-observation loops makes them highly susceptible to adversarial attacks and unintended 
contextual privacy leakage. To mitigate these risks, Wang et al. (2026) developed the 
 
 
 
 
SCHOOL OF COMPUTING

## Page 6

​
              ​
 ​
6   
 
HOLY ANGEL UNIVERSITY 
GUIGuard framework, which identifies and sanitizes sensitive on-screen elements before 
transmitting screenshots to the cloud, though they note that accurate privacy recognition 
remains a major bottleneck. Furthermore, Chen et al. (2025) demonstrated that leveraging 
contextual LLMs can help analyze these privacy policies and present disclosure risks to users 
in real-time. Sun et al. (2026) introduced OS-Sentinel, a hybrid formal verification 
framework that exposes how cloud-based agents routinely execute unsafe operations and 
incur privacy violations in the absence of system-level constraints. 
Taken together, these studies converge on a single structural conclusion: cloud-centric 
processing is not a flawed implementation — it is a flawed foundation. While current 
research proposes sophisticated mitigation strategies—such as on-device screenshot 
sanitization, contextual risk alerts, and hybrid formal verification—these solutions function 
primarily as reactive measures over an inherently flawed architecture that still requires data 
transmission or heavy runtime monitoring. The prevalence of privilege escalation and data 
leakage through routine inter-app interactions proves that simply redacting data for remote 
AI inference is insufficient and often degrades task performance. Therefore, to achieve 
genuine data security and eliminate network-induced latency, there is a clear imperative to 
suggest abandoning cloud dependency entirely. 
The "Reasoning-Execution Gap" in LLM-Powered GUI Agents 
While the tech industry aggressively integrates Large Language Models (LLMs) and 
Vision-Language Models (VLMs) to navigate Graphical User Interfaces (GUIs), recent 
literature reveals a fundamental operational flaw known as the "Execution Gap". Dong et al. 
(2025) diagnosed this gap, revealing that while VLMs frequently generate correct 
 
 
 
 
SCHOOL OF COMPUTING

## Page 7

​
              ​
 ​
7   
 
HOLY ANGEL UNIVERSITY 
intermediate reasoning and deduce user intentions accurately, they consistently fail to 
translate that reasoning into precise, deterministic physical touch coordinates. Ye et al. (2025) 
attempted to resolve this in Mobile-Agent-v3 by using a complex multi-agent framework 
(comprising Manager, Worker, Reflector, and Notetaker agents) to maintain memory and 
self-correct errors dynamically. However, this introduces heavy computational overhead and 
multi-step inference delays that hinder real-time deployment. Similarly, Dai et al. (2025) 
developed V-Droid, which employs LLMs as verifiers rather than generators to pre-evaluate 
finite candidate actions extracted from the accessibility tree, significantly lowering 
decision-making latency to near-real-time but still keeping the core execution probabilistic. 
Addressing the generalization deficits of these models, Liu et al. (2025) introduced LearnAct, 
utilizing a few-shot demonstration framework to teach agents how to execute actions in 
unseen scenarios. Finally, Yang et al. (2025) established the FingerTip 20K benchmark, 
demonstrating that even advanced mobile LLM agents struggle to execute tasks 
deterministically or anticipate user needs without massive longitudinal contextual data. 
Despite multi-agent collaboration, verification-driven parsing, and few-shot 
demonstration learning, the body of evidence confirms that generative models remain 
fundamentally constrained by probabilistic execution — architectural sophistication has not 
closed the execution gap; it has only narrowed it. The documented prevalence of the 
"execution gap" proves that relying on generative AI to deduce exact touch mechanics is 
fundamentally flawed, often resulting in hallucinated interactions or computational overhead 
that is incompatible with offline, real-time use. For assistive technologies, where low latency 
 
 
 
 
SCHOOL OF COMPUTING

## Page 8

​
              ​
 ​
8   
 
HOLY ANGEL UNIVERSITY 
and deterministic accuracy are critical to user trust and safety, these probabilistic methods are 
insufficient. Therefore, to guarantee execution reliability, there is a clear necessity to bypass 
the generative reasoning-execution bottleneck entirely.  
Edge-Based and Offline Speech-to-Intent (S2I) Processing 
​The shift from cloud-dependent to edge-based voice processing is not merely a 
performance optimization — it is an architectural decision with direct implications for 
privacy, reliability, and accessibility. Nelson et al. (2025) engineered an embedded offline AI 
voice assistant for commercial trucks. This system utilizes wake-word detection and an 
offline Speech-to-Intent (S2I) engine to execute vehicular controls entirely without internet 
connectivity. Torkamani and Zarin (2025) designed an adaptive framework balancing local 
and remote inference. Integrating an 8-bit quantized Automatic Speech Recognition (ASR) 
model directly on edge hardware improves real-time responsiveness while mitigating thermal 
and computational stress. Alexandridis et al. (2022) developed TinyS2I. This small-footprint 
utterance classification model reduces memory usage to roughly 2 megabytes by fusing ASR 
and Natural Language Understanding into a single all-neural on-device architecture. 
Mekonnen et al. (2025) successfully deployed an autonomous offline assistive system for the 
visually impaired on a Raspberry Pi 5. Their implementation processes voice commands 
through the VOSK speech recognition engine, achieving zero external data transmission. Di 
Leo et al. (2025) similarly leveraged the VOSK engine to build a real-time speech-to-text 
prototype. Operating entirely on local infrastructure, it achieves sub-second end-to-end 
latency in offline scenarios. 
 
 
 
 
SCHOOL OF COMPUTING

## Page 9

​
              ​
 ​
9   
 
HOLY ANGEL UNIVERSITY 
The literature collectively validates that localized speech recognition is not only 
feasible on constrained mobile hardware but operationally superior to cloud-dependent 
alternatives for privacy-critical applications. Where remote NLP pipelines introduce 
transmission delays and data exposure as unavoidable byproducts of their architecture, 
targeted on-device models eliminate both without sacrificing accuracy. HandsFree applies 
this established paradigm across its five task areas, launching apps, searching, messaging, 
scheduling, and accepting calls, integrating an offline S2I engine with native accessibility 
metadata and adding a preprocessing translation step that normalizes Tagalog and Taglish 
input to English before classification, so that verbal inputs resolve into exact dispatch 
gestures entirely on local hardware with no external network dependency at any stage of 
execution. 
Android Accessibility Service and Deterministic Spatial Navigation​
​
Programmatic extraction of graphical interface metadata bypasses manual touch 
requirements. Xie et al. (2021) engineered an automated control tool utilizing the Android 
Accessibility Service API to identify screen nodes and dispatch simulated gestures directly. 
Arsan et al. (2025) expanded on this architecture, demonstrating that the accessibility service 
can continuously monitor interface events and extract structural view hierarchies during 
runtime. Applications often lack complete metadata. Zhang et al. (2021) addressed this 
deficit by developing a vision-based Screen Recognition model that automatically infers 
accessibility properties from raw pixels. Vu et al. (2023) introduced Voicify, intercepting 
accessibility trees to map verbal inputs to active on-screen targets. Spatial navigation 
 
 
 
 
SCHOOL OF COMPUTING

## Page 10

​
              ​
 ​
10   
 
HOLY ANGEL UNIVERSITY 
techniques complement this programmatic access. Zhu et al. (2009) evaluated grid-based 
speech navigation, showing that recursive grid divisions accelerate target acquisition for 
small interface elements. Dai et al. (2004) similarly validated that mapping speech 
commands to specific grid coordinates improves target selection times compared to 
continuous directional cursor movement. 
Operating system-level metadata extraction provides a direct pathway for executing 
touch events. Generative visual models frequently misinterpret interactive targets. The 
Android Accessibility Service API bypasses these interpretation errors by exposing the exact 
coordinates and functional states of active interface nodes. Coupling this explicit node data 
with structured spatial subdivisions enables deterministic targeting. Voice commands mapped 
to specific grid coordinates or direct node identifiers remove probabilistic decision-making. 
HandsFree leverages this metadata to identify actionable interface elements, normalizes any 
Tagalog or Taglish tokens to English, and executes the corresponding gesture entirely on 
local hardware. 
Spatial Navigation and Non-Visual Touchscreen Accessibility 
Sequential audio narration imposes heavy cognitive loads on users with visual or 
motor impairments navigating touchscreens. Spatial interaction models replace this linear 
scanning with deterministic, structured frameworks. Gonzalez Penuela et al. (2025) 
developed TapNav, an adaptive spatiotactile screen reader that utilizes physical markers to 
constrain user input to specific screen regions, shifting cognitive demand from auditory 
memory to tactile recognition. Li et al. (2022) engineered TangibleGrid, translating visual 
 
 
 
 
SCHOOL OF COMPUTING

## Page 11

​
              ​
 ​
11   
 
HOLY ANGEL UNIVERSITY 
layouts into resizable tangible brackets on a grid baseboard to facilitate independent spatial 
comprehension. Lee et al. (2024) observed that tile-based spatial exploration aligns closely 
with natural spatial cognition, allowing non-linear interface navigation without extensive 
audio instructions. Grussenmeyer and Folmer (2016) demonstrated that overlaying a 
structured geometric grid on touchscreens accelerates target acquisition by minimizing the 
need for continuous directional tracking. 
Sequential screen reading places a significant memory and cognitive burden on users 
who cannot visually scan an interface, while probabilistic cursor targeting compounds this 
with unpredictable error rates. The spatial navigation literature consistently demonstrates that 
imposing a structured geometric framework over an interface resolves both problems 
simultaneously — users can target discrete regions directly rather than scanning toward them. 
HandsFree applies this same idea: a grid appears over the screen, and the user says which 
part they want to interact with. Any Tagalog or Taglish words in the command are converted 
to English first, so the instruction is understood correctly, all without touching the screen. 
Filipino NLP and Speech Recognition for Tagalog and Taglish 
A significant challenge in developing voice assistants for Filipino users is the 
widespread practice of mixing Tagalog and English within the same sentence, commonly 
referred to as Taglish. Most speech recognition systems are trained on a specific single 
language and are not designed to handle two languages being mixed together, leading to 
recognition failures when users switch languages mid-command. Montalan et al. (2025) 
demonstrated through BATAYAN, a Filipino NLP benchmark developed with native-speaker 
 
 
 
 
SCHOOL OF COMPUTING

## Page 12

​
              ​
 ​
12   
 
HOLY ANGEL UNIVERSITY 
input, that Filipino and Taglish remain under-represented in existing language models, and 
that even commercial AI systems perform poorly on Filipino language tasks, confirming that 
standard multilingual approaches are insufficient for the Filipino context. Malaay and 
Ventura (2024) addressed this directly by building a speech recognition system trained on 
real Filipino-English broadcast speech, achieving a 96.05% accuracy rate and attributing that 
result to the inclusion of Taglish data during training rather than relying on monolingual 
corpora. Lim et al. (2022) further found that the frequency of code-switching within a 
statement does not affect recognition accuracy, meaning the problem cannot be resolved 
through calibration alone; a well-performing Filipino ASR system must be capable of 
handling language switching at any point in a spoken command. 
Collectively, these studies establish that Taglish is not an edge case. Still, the norm in 
Filipino communication is that standard monolingual or generic multilingual systems are not 
equipped to handle it. The most viable approach for a system running on an ordinary mobile 
phone is to convert mixed-language input into one consistent language before processing 
begins, rather than relying on a demanding multilingual model. HandsFree applies this 
principle directly; before any voice command is processed, Tagalog and Taglish phrases are 
converted to their English equivalents through a fixed reference list, ensuring that commands 
spoken in any combination of the two languages are interpreted consistently, without 
requiring an internet connection at any stage. 
Voice Interfaces for Mobile Productivity and Time Management 
 
 
 
 
SCHOOL OF COMPUTING

## Page 13

​
              ​
 ​
13   
 
HOLY ANGEL UNIVERSITY 
Voice interfaces offer a practical way to handle time management tasks on mobile 
devices, particularly for users who cannot rely on physical interaction. Pradhan et al (2018) 
found that people with disabilities valued voice control for setting alarms, managing 
reminders, and organizing appointments, but encountered failures when systems required the 
exact phrasing that did not match their natural speech. Upadhyay et al. (2023) further 
provided that scheduling and alarm commands were among the most sustained everyday uses 
of voice assistants, valued because they removed the need to physically touch a device, yet 
users remained unable to access more advanced reminder features due to strict command 
requirements.  
Both studies point to the same gap: systems that demand precise input exclude the 
users who need hands-free access most, a limitation HandsFree addresses through a flexible 
fuzzy-matching approach and a predefined intent vocabulary that accepts natural variation in 
how the same command is phrased. 
 
 
 
Conceptual Framework 
 
 
 
 
 
SCHOOL OF COMPUTING

## Page 14

​
              ​
 ​
14   
 
HOLY ANGEL UNIVERSITY 
 
 
Figure 1 IPO Diagram 
The HandsFree system operates on an Input-Process-Output (IPO) architecture in 
which every stage executes entirely on local device hardware. No audio data, screen 
metadata, or user information is transmitted to any external server at any point in the 
pipeline. The revised framework below reflects the system's expanded scope, which now 
encompasses App Launching, Smart Search, Messaging, Accepting Calls, and Scheduling, in 
addition to general Android navigation. It also incorporates the Translation Layer as a new 
 
 
 
 
SCHOOL OF COMPUTING

## Page 15

​
              ​
 ​
15   
 
HOLY ANGEL UNIVERSITY 
processing component that normalizes multilingual Filipino input into English before intent 
classification. 
Input Layer 
The input layer consists of two concurrent data streams. The first is raw audio 
captured from the device microphone, which accepts spoken commands in Tagalog, English, 
or any combination of the two (Taglish). The system does not require the user to speak in a 
single language or to switch languages between commands; the Translation Layer, described 
below, normalizes all multilingual input transparently. The second stream is real-time 
structural metadata continuously extracted from the active foreground application through the 
Android Accessibility Service API. This metadata exposes the underlying hierarchy of the 
active application, including element types, labels, and absolute screen coordinates, as 
structured machine-readable node data. 
Process Layer 
Audio Preprocessing Stage 
Before the transcription of spoken commands can begin, the raw audio signal 
captured by the device microphone undergoes a preprocessing stage. This stage applies noise 
filtering techniques, specifically spectral subtraction and voice activity detection, to attenuate 
background acoustic interference and to isolate the speech segment from surrounding 
ambient noise. Preprocessing is a necessary architectural step for an offline system because, 
unlike cloud-based ASR engines that can leverage large server-side noise-robust acoustic 
models, the on-device ASR engine operates under memory and computational constraints 
 
 
 
 
SCHOOL OF COMPUTING

## Page 16

​
              ​
 ​
16   
 
HOLY ANGEL UNIVERSITY 
that make it more sensitive to signal quality. By cleaning the audio input prior to 
transcription, the preprocessing stage increases the accuracy of subsequent speech 
recognition and reduces the likelihood of misclassification downstream in the intent pipeline. 
This is particularly important for the target deployment context, where users may issue 
commands in variable acoustic environments such as public transit, workplaces, or homes. 
The preprocessed audio is forwarded to the Offline Speech-to-Text engine, which 
transcribes the spoken command into text entirely on-device without transmitting audio to 
any external server. The acoustic model underlying the ASR engine is sourced from a 
pre-trained engine supported by publicly available multilingual corpora, including the 
Mozilla Common Voice Filipino corpus for Tagalog speech data and LibriSpeech for English 
speech data, ensuring coverage across both languages present in Filipino user input. 
The transcribed text is then passed to the Translation Layer, the architectural 
component that distinguishes HandsFree from existing assistants. Because spoken Filipino 
communication frequently mixes Tagalog and English within a single utterance, a 
phenomenon known as Taglish, the intent classification model cannot reliably process raw 
multilingual transcriptions without normalization. The Translation Layer addresses this by 
detecting Tagalog and Taglish tokens in the transcribed text and converting them to their 
English equivalents through a deterministic token-mapping vocabulary, producing a 
normalized English string as output. This normalized output is then passed to the NLP and 
Intent Classification stage for uniform processing. 
 
 
 
 
SCHOOL OF COMPUTING

## Page 17

​
              ​
 ​
17   
 
HOLY ANGEL UNIVERSITY 
The Intent Classification stage evaluates the normalized English transcription using a 
Levenshtein Distance algorithm, which performs fuzzy string matching against a predefined 
intent vocabulary. This accommodates natural pronunciation variations, regional accents, and 
minor disfluencies without requiring exact phrasing. Simultaneously, a Depth-First Search 
(DFS) algorithm traverses the captured accessibility node hierarchy to isolate actionable UI 
targets relevant to the recognized intent. A Finite State Machine (FSM) governs the distinct 
phases of system operation, enforcing rigid programmatic transitions between idle, listening, 
preprocessing, translating, processing, and execution states to prevent concurrent or 
conflicting command processing. 
The Translation Layer: Multilingual Input Normalization 
            The Translation Layer is the component that enables HandsFree to accept voice 
commands in Tagalog, English, or Taglish without requiring the user to switch languages or 
to conform to a single linguistic register. Upon receiving the transcribed text from the offline 
ASR engine, the Translation Layer tokenizes the string and evaluates each token against a 
bilingual lexicon that maps common Tagalog navigation-related terms and Taglish hybrid 
expressions to their English equivalents. Tokens that are already in English are passed 
through unmodified. Tokens identified as Tagalog or code-switched Taglish are replaced with 
their English counterparts. The resulting normalized English string is then forwarded to the 
intent classification stage. 
            This design decision normalizing multilingual input to a single target language before 
classification is architecturally significant for several reasons. First, it decouples language 
 
 
 
 
SCHOOL OF COMPUTING

## Page 18

​
              ​
 ​
18   
 
HOLY ANGEL UNIVERSITY 
support from the intent model: adding a new supported language requires only updating the 
bilingual lexicon, not retraining the entire classification pipeline. Second, it preserves the 
deterministic properties of the Levenshtein Distance matching algorithm, which operates on a 
fixed English vocabulary and would otherwise require separate vocabulary sets per language. 
Third, it allows the system to handle mid-utterance code-switching, which is characteristic of 
natural Filipino speech, without requiring the ASR engine to be multilingual itself. The 
Translation Layer therefore functions as the architectural bridge between the multilingual 
realities of Filipino mobile users and the monolingual English processing pipeline that 
guarantees deterministic, offline intent classification. 
Output Layer 
            System outputs manifest as deterministic physical interactions with the active 
application. Matched intents are mapped to absolute spatial coordinates either from identified 
accessibility nodes or from a geometric grid overlay projected on the active interface and the 
accessibility service dispatches these as simulated gestures, triggering tap, swipe, or scroll 
events directly on the target application. The expanded output set now includes App Launch 
commands that open third-party applications by name, Smart Search queries dispatched to 
the device search interface, outbound Messaging commands that compose and send 
messages, incoming Call Acceptance gestures, and Scheduling actions including creating, 
reading, and checking reminders and alarms through Android system APIs. The entire 
process from voice input to gesture execution occurs without any cloud dependency, 
 
 
 
 
SCHOOL OF COMPUTING

## Page 19

​
              ​
 ​
19   
 
HOLY ANGEL UNIVERSITY 
producing a response pipeline capable of sub-second execution on standard consumer-grade 
Android hardware. 
Objectives of the Study  
This study will develop, implement, and evaluate HandsFree, an offline voice-controlled 
Android application that supports hands-free mobile use across five core functions: launching 
applications, performing searches, sending messages, accepting incoming calls, and 
managing schedules. The system will process voice commands in Tagalog, English, and 
Taglish by normalizing all input to English before intent classification, enabling consistent 
command recognition for Filipino users without requiring a network connection. The study 
will further assess the usability and accessibility compliance of the system through structured 
evaluation with general Android users and technical evaluators. 
Specific Objectives: 
●​ The study will develop and deploy an offline Speech-to-Intent (S2I) engine paired 
with a Levenshtein Distance algorithm to process voice input locally on the device. 
The engine will perform fuzzy matching against a predefined intent vocabulary to 
handle natural speech variation, and a translation layer will normalize Tagalog, 
English, and Taglish input to English before classification so that the intent 
recognition model can process all commands uniformly regardless of the language 
used. 
 
 
 
 
SCHOOL OF COMPUTING

## Page 20

​
              ​
 ​
20   
 
HOLY ANGEL UNIVERSITY 
●​ The study will extract real-time structural metadata from active applications using the 
Android Accessibility Service API, and a Depth-First Search (DFS) algorithm will 
traverse the view hierarchy to isolate actionable screen nodes, mapping recognized 
intents to absolute spatial coordinates without requiring modification or cooperation 
from third-party application developers. 
●​ The system will be designed to operate across five core task areas: App Launching, 
Smart Search, Messaging, Accepting Calls, and Scheduling. Each task area will be 
implemented as a modular intent category within the voice command vocabulary, 
covering the full scope of intended hands-free use on standard consumer-grade 
Android devices. 
●​ The study will design a deterministic coordinate mapping mechanism that projects an 
absolute geometric grid over the graphical interface. This approach will bypass the 
generative interpretation errors common in probabilistic visual models and will 
support modular expansion, meaning new task functions can be added or removed 
from the intent vocabulary without redesigning the core pipeline or the coordinate 
mapping layer. 
●​ The study will implement a Finite State Machine (FSM) to govern the application 
lifecycle through rigid programmatic state transitions, and the completed system will 
be evaluated through structured usability testing with general Android users aged 18 
and above as well as technical evaluators with Android development experience. 
Evaluation will assess intent recognition accuracy, translation layer performance, 
 
 
 
 
SCHOOL OF COMPUTING

## Page 21

​
              ​
 ​
21   
 
HOLY ANGEL UNIVERSITY 
end-to-end processing latency, and overall usability as measured by the System 
Usability Scale. 
Scope and Delimitations 
This study will encompass the development and evaluation of an offline voice-controlled 
Android application, programmed in Kotlin using Android Studio and Visual Studio Code, 
and limited strictly to the Android platform. The target hardware will include standard 
consumer-grade smartphones prevalent in the Philippine market, specifically devices running 
Android 11.0 through Android 15.0. The system will accept voice commands in Tagalog, 
English, and Taglish, and a translation layer will normalize all input to English before the 
intent classification stage so that the Speech-to-Intent engine can process commands 
consistently regardless of which language or combination of languages the user speaks. The 
five core functions in scope are App Launching, Smart Search, Messaging, Accepting Calls, 
and Scheduling, where Scheduling covers creating, reading, and checking reminders and 
alarms through Android system APIs. The feature set is designed to be modular, meaning 
individual functions can be added to or removed from the intent vocabulary without requiring 
changes to the core processing pipeline, the FSM, or the coordinate mapping layer. This 
modularity is a deliberate architectural decision rather than a limitation. The system will 
leverage the Android Accessibility Service API to extract active view hierarchies and a 
Depth-First Search algorithm to map interactive screen nodes to absolute spatial coordinates. 
Physical interaction will be simulated through a predefined gesture vocabulary of 
 
 
 
 
SCHOOL OF COMPUTING

## Page 22

​
              ​
 ​
22   
 
HOLY ANGEL UNIVERSITY 
single-finger taps, linear swipes, and basic scrolls, all executed without any external network 
dependency at any stage of the pipeline. 
Several operational boundaries will define the limits of the proposed system. 
HandsFree explicitly will exclude functionality within secure or protected graphical 
interfaces, as Android operating system protocols will intentionally block the accessibility 
service from reading metadata on screens that enforce system-level privacy flags; the 
application will therefore be inactive during password input, banking operations, and 
DRM-protected media playback. The Android Accessibility Service API, which the system 
depends on, will be able to read view hierarchies and dispatch simulated gestures in standard 
third-party applications, but will not be able to interact with system-level overlays that draw 
above all other layers, screens that explicitly disable accessibility metadata, or applications 
that use non-standard rendering pipelines that do not expose node data through the 
accessibility framework. Complex multi-touch interactions, such as pinch-to-zoom or 
multi-finger rotations, will be excluded from the gesture vocabulary. Scheduling functions 
will be limited to creating, reading, and checking reminders and alarms through Android 
system APIs; calendar event management and third-party scheduling applications will fall 
outside the scope of this iteration. Voice capture quality will depend on the microphone 
hardware of the host device, and the system will not be able to compensate for the limitations 
of low-quality or damaged device microphones. System responsiveness and processing 
latency will be bound by the localized hardware capabilities of the host device, meaning 
performance may vary on lower-tier smartphones with restricted processing power and 
 
 
 
 
SCHOOL OF COMPUTING

## Page 23

​
              ​
 ​
23   
 
HOLY ANGEL UNIVERSITY 
memory. The offline speech recognition engine will not receive adaptive updates available to 
cloud-connected systems, and transcription accuracy may be reduced in environments with 
significant background acoustic noise or when processing strong regional accents not 
well-represented in the engine’s training data. 
Methodology  
Research Design 
​This study will adopt an applied, mixed-methods research design to develop and 
evaluate the HandsFree navigation system. Quantitative analysis will measure objective 
system performance metrics—specifically, end-to-end processing latency, speech recognition 
accuracy, and on-device memory consumption during offline inference. Qualitative analysis 
will assess the functional utility and user experience of the interface through structured 
usability testing with the target demographic. The combination of system-based performance 
testing with user-centric evaluation will provide a comprehensive assessment of the 
application's operational viability and real-world suitability as a mobile productivity tool (Shi 
et al., 2025). 
The Android application overlay will be developed following the Rapid Application 
Development (RAD) methodology (see Figure 2, Appendix A), which will prioritize iterative 
prototyping over rigid sequential planning. This approach will enable continuous integration 
of interface adjustments in response to early testing constraints specific to the Android 
Accessibility Service API. Concurrently, the offline Speech-to-Intent processing engine will 
adhere to the Cross-Industry Standard Process for Data Mining (CRISP-DM) (see Figure 3, 
Appendix B), which will govern the machine learning pipeline through a structured 
 
 
 
 
SCHOOL OF COMPUTING

## Page 24

​
              ​
 ​
24   
 
HOLY ANGEL UNIVERSITY 
progression encompassing data understanding, intent vocabulary preparation, model training, 
and offline deployment evaluation. 
Sources of Data 
Primary data will form the foundation of this study, derived directly from controlled 
system testing and structured user evaluations. The data will be categorized into statistical 
and non-statistical formats. Statistical data will consist of objective performance metrics 
extracted during live application execution, including end-to-end processing latency, offline 
speech recognition accuracy, and on-device memory consumption. Non-statistical data will 
originate from the qualitative usability testing phase and will comprise direct observational 
field notes, task completion outcomes, and structured feedback gathered from the target 
demographic. Collecting both data types will ensure a comprehensive evaluation of 
operational viability alongside interface utility. 
Participants 
Participant selection will employ a combination of purposive and snowball sampling 
methodologies (Fairman et al., 2025). Purposive sampling will isolate individuals meeting 
specific eligibility criteria: the primary stakeholder group will consist of adults aged 18 and 
above that regularly use mobile devices as a primary communication and/or productivity 
device. Snowball sampling will also expand recruitment reach beyond initial contacts, with 
enrolled participants allowed and highly recommended to refer to peers who have mobile 
usage habits and/or are more familiar with the specifications and features with their device. 
A secondary group consisting of one or two mobile application developers with 
experience in Android development will also be part of the participant group. These 
 
 
 
 
SCHOOL OF COMPUTING

## Page 25

​
              ​
 ​
25   
 
HOLY ANGEL UNIVERSITY 
evaluators will assess the technical integrity of the system, evaluate the technical soundness 
of its software architecture against current Android development standards, and the system’s 
practical suitability to its intended user base. 
The combined sampling strategy will ensure the intended application is evaluated 
across a range of general users while also receiving targeted technical scrutiny from 
professionals that are well positioned to assess its implementation quality. 
Instruments 
Data acquisition will rely on both technical logging instruments and standardized 
subjective measures administered through Google Forms to facilitate efficient data collection 
and automated tabulation. 
For general end-users, the System Usability Scale (SUS) will serve as the primary 
subjective instrument for this study. The SUS will consist of ten (10) items measured on a 
five-point Likert scale (1 = Strongly Disagree to 5 = Strongly Agree), organized into 
usability items assessing the app’s ease of use, learnability, and complexity that assesses 
unnecessary friction and the need for external support. The calculated SUS score, ranging 
from 0 to 100, will quantify overall interface usability and user satisfaction. Task scenario 
scripts will provide standardized testing parameters through predefined prompts covering 
interaction categories such as: launching an application, conducting a search through the 
search bar, sending a message, accepting a call, and creating or checking a scheduled entry, 
which ensures consistent and comparable evaluation conditions across all participants 
(Fairman et al., 2025). A sample end-user survey instrument is provided in Appendix D. 
 
 
 
 
SCHOOL OF COMPUTING

## Page 26

​
              ​
 ​
26   
 
HOLY ANGEL UNIVERSITY 
For expert evaluators, a structured Expert Evaluation Questionnaire will be 
administered via Google Forms. This instrument will consist of fifteen items distributed 
across three categories: Technical Accuracy, assessing the precision of voice command 
recognition and gesture execution across five items; Mobile Usability and Design Standards, 
evaluating the system's design against established Android mobile usability standards across 
five items; and Practical Development Utility, assessing how well the system serves its 
intended user base from a professional development perspective across five items. Each item 
will be rated on a five-point Likert scale ranging from 1 as Strongly Disagree to 5 as Strongly 
Agree. A sample expert evaluation instrument is provided in Appendix E. 
System-based profiling tools will record objective software performance, capturing 
end-to-end processing latency, memory consumption, and intent classification accuracy 
during offline natural language processing execution. 
Data Collection 
The data collection procedure will execute in controlled sequential phases. Initial 
setup will involve installing the HandsFree application on target consumer-grade android 
devices. Participants will complete a brief orientation to understand the predefined voice 
command vocabulary, the translation layer behavior for Tagalog, English, and Taglish input, 
and grid-based coordinate mapping where applicable.  
​During the quantitative testing phase, researchers will execute standardized voice 
commands across core task categories to isolate system performance. Internal logging tools 
will capture real-time computational metrics and response latencies independent of user 
interaction variables.  
 
 
 
 
SCHOOL OF COMPUTING

## Page 27

​
              ​
 ​
27   
 
HOLY ANGEL UNIVERSITY 
The qualitative phase will shift focus to human-device interaction. Participants will 
perform standardized navigational tasks while researchers will record observational field 
notes covering command recognition success, translation accuracy between Tagalog and 
English input, gesture execution success and transcription errors. Following task completion, 
participants will accomplish the System Usability Scale questionnaire via Google Forms. All 
Google Forms responses will be exported as CSV files and imported into the Statistical 
Package for the Social Sciences (SPSS) or other applicable medium for quantitative 
processing. Semi-structured exit interviews will be conducted after questionnaire completion 
to capture additional feedback regarding interface naturalness, language handling, and overall 
task confidence. Interview responses will be transcribed by the research team and subjected 
to inductive thematic analysis. 
Data Analysis 
The data analysis procedure will employ both quantitative and qualitative approaches 
to comprehensively evaluate the HandsFree system. For quantitative data, Python scripts will 
parse internal system logs to extract objective performance figures including end-to-end 
processing latency measurements and memory consumption values recorded during testing 
sessions. The Statistical Package for the Social Sciences or other applicable medium, will 
process these raw computational metrics to generate descriptive statistics. Frequency 
distributions and mean scores will be computed for all Likert-scale responses from both the 
SUS questionnaire and the Expert Evaluation Questionnaire, with standard deviations 
reported to reflect variability across ratings. 
 
 
 
 
SCHOOL OF COMPUTING

## Page 28

​
              ​
 ​
28   
 
HOLY ANGEL UNIVERSITY 
For system performance metrics the following measures will be applied. End-to-end 
processing latency will be calculated as elapsed time in milliseconds from voice input 
detection to gesture execution dispatch. Speech recognition accuracy will be computed as the 
ratio of correctly classified intents to total commands issued. Translation accuracy will be 
measured as the proportion of Tagalog and Taglish inputs correctly normalized to their 
English intent equivalent before classification. 
SUS scores will be calculated using the standard formula where odd-numbered item 
scores are the scale position minus one and even-numbered item scores are five minus the 
scale position. The sum of all converted scores is multiplied by 2.5 to produce a final SUS 
score out of 100. Mean SUS scores will be interpreted against established benchmarks where 
scores above 68 are considered above average and scores above 80 are considered excellent. 
Non-statistical data from observational field notes and exit interviews will undergo 
inductive thematic analysis in which researchers assign structured codes to recurring 
interaction patterns, command recognition failures, and language handling inconsistencies 
identified across participants. This combined analytical approach will contextualize objective 
hardware performance within the general user experience, providing a holistic evaluation of 
the system across its intended deployment conditions. 
Research Procedure 
        
The technical implementation of HandsFree will follow a dual-model development 
framework reflecting its two parallel construction tracks: the Android application overlay and 
 
 
 
 
SCHOOL OF COMPUTING

## Page 29

​
              ​
 ​
29   
 
HOLY ANGEL UNIVERSITY 
the offline Speech-to-Intent processing engine. Each track is governed by a distinct 
methodology suited to its respective nature. 
         The Android application overlay encompassing the FSM lifecycle controller, the DFS 
node locator, the Translation Layer, the gesture dispatcher, and the grid overlay interface will 
be constructed following the Rapid Application Development (RAD) methodology (see 
Figure 2, Appendix A). RAD prioritizes iterative prototyping over rigid sequential planning, 
which is appropriate here because the dynamic constraints of the Android Accessibility 
Service API require continuous testing against live device environments. Initial development 
phases will establish the system requirements and define the functional intent vocabulary. 
Developers will then construct interactive prototypes that test the deterministic coordinate 
mapping mechanism and validate FSM state transitions. The Translation Layer will be 
developed and validated during these cycles, with the bilingual lexicon expanded iteratively 
based on observed misclassification patterns during testing. Continuous integration loops will 
identify and resolve gesture dispatch anomalies throughout active development, with 
successive iterations refining both the overlay interface and the lexicon based on early 
functional feedback. The final phase will stabilize the Kotlin codebase and prepare the 
application for formal usability evaluation. 
 
 
 
 
 
SCHOOL OF COMPUTING

## Page 30

​
              ​
 ​
30   
 
HOLY ANGEL UNIVERSITY 
 
Figure 2 The Rapid Application Development (RAD) Model Lifecycle. 
(GeeksForGeeks, 2026) 
           ​ The offline Speech-to-Intent (S2I) engine will be developed in parallel following the 
Cross-Industry Standard Process for Data Mining (CRISP-DM) (see Figure 3, Appendix B). 
The scope of CRISP-DM within this project is limited to the framework that governs the 
intent vocabulary preparation and evaluation phases, not the training of the raw Automatic 
Speech Recognition (ASR) acoustic model. The acoustic model is sourced from a pre-trained 
ASR engine, specifically, the VOSK speech recognition toolkit whose underlying acoustic 
models are trained on publicly available speech corpora. The English acoustic model is 
backed by the LibriSpeech corpus, while the Filipino acoustic model is backed by the 
Mozilla Common Voice Filipino corpus, which provides crowdsourced Filipino speech data 
sufficient for offline on-device inference on consumer-grade Android hardware. 
 
 
 
 
SCHOOL OF COMPUTING

## Page 31

​
              ​
 ​
31   
 
HOLY ANGEL UNIVERSITY 
 
 
Figure 3 The Cross-Industry Standard Process for Data Mining (CRISP-DM) Lifecycle. 
(Hotz, 2025) 
           ​ Within the CRISP-DM pipeline, the business understanding and data understanding 
stages will define the operational boundaries for offline voice command execution and 
catalog the speech patterns, linguistic registers, and Taglish code-switching behaviors 
characteristic of the target Filipino user population. The data preparation stage will 
standardize raw audio inputs from both corpora and construct the specific English intent 
vocabulary that serves as the classification target after the Translation Layer normalizes 
multilingual input. The modeling stage will integrate the pre-trained VOSK ASR engine with 
the Levenshtein Distance algorithm for fuzzy intent matching, configuring the matching 
thresholds to tolerate natural pronunciation variation without producing false positive 
classifications. The evaluation phase will measure fuzzy matching accuracy against held-out 
 
 
 
 
SCHOOL OF COMPUTING

## Page 32

​
              ​
 ​
32   
 
HOLY ANGEL UNIVERSITY 
audio samples, assess Translation Layer normalization coverage across Taglish test inputs, 
and record memory consumption and end-to-end processing latency on constrained 
consumer-grade Android hardware. Deployment will embed the finalized classification 
engine and translation lexicon into the mobile application environment, with all audio 
processing and language normalization operating strictly on-device without any external 
network transmission at any stage of the pipeline. 
 
Figure 4 Proposed Architectural Design of HandsFree 
Dataset Sources for Acoustic Model and Intent Evaluation 
            ​The acoustic models underlying the offline ASR engine are drawn from two publicly 
available speech corpora. For English speech data, the system relies on the LibriSpeech 
 
 
 
 
SCHOOL OF COMPUTING

## Page 33

​
              ​
 ​
33   
 
HOLY ANGEL UNIVERSITY 
corpus (Panayotov et al., 2015), a large-scale read-speech dataset derived from LibriVox 
audiobook recordings comprising approximately 1,000 hours of English speech at 16 kHz 
sampling. LibriSpeech is widely used as a benchmark corpus for ASR research and provides 
broad phonetic and lexical coverage for general English spoken commands. For Filipino 
speech data, the system relies on the Mozilla Common Voice Filipino corpus (Mozilla 
Foundation, 2023), an open-source crowdsourced multilingual speech dataset that includes 
validated Filipino audio contributions from native speakers across a range of regional accents 
and recording environments. The Mozilla Common Voice project is specifically designed to 
improve ASR accessibility for underrepresented languages, making it the most appropriate 
publicly available resource for building acoustic robustness to Filipino-accented English and 
Tagalog phonology. Together, these two corpora provide the acoustic foundation for the 
VOSK pre-trained models that the HandsFree system deploys on-device, while the intent 
vocabulary and Taglish normalization lexicon are constructed by the research team during the 
CRISP-DM data preparation phase using domain-specific command patterns identified 
during the business understanding stage. 
             ​Figure 4 presents the revised proposed architectural design of the HandsFree system, 
organized into four distinct functional layers. Together these layers constitute a complete, 
closed-loop voice-to-gesture pipeline that operates entirely on local device hardware. The 
pipeline has been updated from the original design to include the Translation Layer as an 
explicit processing component within the HandsFree Overlay Layer, reflecting the system's 
expanded support for Tagalog, English, and Taglish voice input. 
 
 
 
 
SCHOOL OF COMPUTING

## Page 34

​
              ​
 ​
34   
 
HOLY ANGEL UNIVERSITY 
User Input Layer 
             ​The User Input Layer serves as the entry point of the system. This layer captures raw 
spoken commands through the device microphone, accepting voice input in Tagalog, English, 
or Taglish without requiring the user to select a language mode. Upon detecting a spoken 
command, the digitized audio stream is passed downstream to the HandsFree Overlay Layer 
for preprocessing and processing. No audio data leaves the device at this stage or at any 
subsequent stage of the pipeline. 
HandsFree Overlay Layer 
             ​The HandsFree Overlay Layer constitutes the core intelligence of the system and 
contains five sequential processing components. First, the audio signal passes through a 
Preprocessing Stage that applies noise filtering and voice activity detection to attenuate 
background acoustic interference and isolate the speech segment. Second, the cleaned audio 
is forwarded to the Offline Speech-to-Text (ASR) Engine, which transcribes spoken words 
into text entirely on-device using the VOSK pre-trained acoustic models, without 
transmitting audio to any external server. 
           ​Third, the transcribed text enters the Translation Layer, the new architectural 
component introduced in this revision. The Translation Layer detects Tagalog and Taglish 
tokens in the transcribed text and replaces them with their English equivalents through a 
deterministic bilingual lexicon, producing a normalized English string. This normalized 
output is what the subsequent classification components receive, ensuring that the intent 
 
 
 
 
SCHOOL OF COMPUTING

## Page 35

​
              ​
 ​
35   
 
HOLY ANGEL UNIVERSITY 
matching pipeline operates on uniform English input regardless of the language in which the 
original command was spoken. 
          ​Fourth, the normalized English text is evaluated by the Finite State Machine (FSM), 
which governs the application lifecycle by enforcing rigid state transitions between idle, 
listening, preprocessing, translating, processing, and execution phases. The FSM ensures that 
commands are processed sequentially and that no conflicting or concurrent interactions 
occur. Fifth, the Depth-First Search (DFS) Node Locator receives the resolved intent from 
the FSM, traverses the active accessibility node hierarchy, and identifies the specific UI 
element targeted by the voice command, mapping the resolved intent to an absolute set of 
screen coordinates. 
Operating System Layer 
          ​The Operating System Layer interfaces directly with the Android Accessibility Service 
API, which serves as the bridge between the HandsFree overlay and the underlying Android 
operating system. This layer retrieves real-time structural metadata from the active 
application's view hierarchy — including element labels, types, and bounding coordinates — 
and provides this data to the DFS Node Locator. It also receives resolved spatial coordinates 
from the overlay layer and dispatches these as programmatic gesture events through the 
accessibility service framework, translating abstract intents into concrete physical 
interactions. 
Target Layer 
 
 
 
 
SCHOOL OF COMPUTING

## Page 36

​
              ​
 ​
36   
 
HOLY ANGEL UNIVERSITY 
        ​The Target Layer represents the active third-party Android application currently 
displayed on the device screen. This layer is the final recipient of the simulated dispatch 
gestures, taps, swipes, scrolls, or app-launch intents, generated by the system. Crucially, the 
target application requires no prior modification, custom integration, or developer 
cooperation to receive these simulated gestures. The Android Accessibility Service API 
allows HandsFree to interact with any foreground application universally, making the system 
application-agnostic by design and extending its utility across the full ecosystem of Android 
applications without restriction. 
Ethical Considerations 
The testing protocol will be governed by two non-negotiable priorities: the welfare of 
all participants and the strict confidentiality of all data to be collected during the study. Prior 
to data collection, all participants will receive comprehensive details regarding study 
objectives, procedures, and potential risks, and will provide voluntary written informed 
consent. Participants will retain the unconditional right to withdraw from the evaluation at 
any point without penalty or consequence. The HandsFree application design will inherently 
minimize privacy risks, as processing all natural language entirely on local edge hardware 
will prevent the external transmission of ambient audio recordings or sensitive screen 
metadata at any stage of system operation. All recorded demographic information and 
qualitative interview feedback will be immediately anonymized upon collection, and 
de-identified evaluation records will be stored on encrypted, password-protected local 
storage environments accessible exclusively to the primary investigating team. All raw 
 
 
 
 
SCHOOL OF COMPUTING

## Page 37

​
              ​
 ​
37   
 
HOLY ANGEL UNIVERSITY 
participant data will undergo secure deletion upon conclusion of the study to prevent 
unauthorized future retrieval or secondary use. 
 
References 
Alexandridis, A., Sathyendra, K. M., Strimel, G. P., Kveton, P., Webb, J., & 
Mouchtaris, A. (2022). TinyS2I: A small-footprint utterance classification model with 
contextual support for on-device SLU. ICASSP 2022-2022 IEEE International Conference on 
Acoustics, Speech and Signal Processing. 
Arsan, D., Guo, C., Wellyanto, M. R., Ji, E., Talton, J. O., & Kumar, R. (2025). 
On-Device Interaction Mining. Proc. ACM Hum.-Comput. Interact. 
Chen, C., Zhou, D., Ye, Y., Li, T. J., & Yao, Y. (2025). CLEAR: Towards Contextual 
LLM-Empowered Privacy Policy Analysis and Risk Generation for Large Language Model 
Applications. Proceedings of the 30th International Conference on Intelligent User 
Interfaces. 
​Cheng, J., Dankers, V., & Ponti, E. M. (2023). Towards low-resource code-switching 
speech recognition. arXiv preprint arXiv:2304.12397. 
Dai, G., Jiang, S., Cao, T., Li, Y., Yang, Y., Tan, R., Li, M., & Qiu, L. (2025). 
Advancing mobile GUI agents: A verifier-driven approach to practical deployment. arXiv 
preprint. 
 
 
 
 
SCHOOL OF COMPUTING

## Page 38

​
              ​
 ​
38   
 
HOLY ANGEL UNIVERSITY 
Dai, L., Goldman, R., Sears, A., & Lozier, J. (2004). Speech-based cursor control: A 
study of grid-based solutions. ACM SIGACCESS Accessibility and Computing. 
Di Leo, S., De Cicco, L., & Mascolo, S. (2025). Real-Time Speech-to-Text on Edge: 
A Prototype System for Ultra-Low Latency Communication with AI-Powered NLP. 
Information. 
Dong, L., Zhou, Z., Yang, S., Sheng, H., Cheng, P., Wu, Z., Wu, Z., Liu, G., & Zhang, 
Z. (2025). Say one thing, do another? Diagnosing reasoning-execution gaps in 
VLM-powered mobile-use agents. arXiv preprint. 
Fairman, A. D., Indradhirmaya, F. A., Osal, R. B., & Saptono, A. (2025). Iterative 
User-Centered Design of the Mobile Device Assessment Tool (MoDAT). Technologies, 
13(8), 358. 
GeeksforGeeks. (2026, January 19). Rapid application development model (RAD) - 
software engineering. 
https://www.geeksforgeeks.org/software-engineering/software-engineering-rapid-application-
development-model-rad/ 
Gonzalez Penuela, R. E., Liu, F., Macintyre, B., & Saffo, D. (2025). TapNav: 
Adaptive spatiotactile screen readers for tactually guided touchscreen interactions for blind 
and low vision people. Proceedings of the ACM on Interactive, Mobile, Wearable and 
Ubiquitous Technologies, 9(4), Article 178. 
 
 
 
 
SCHOOL OF COMPUTING

## Page 39

​
              ​
 ​
39   
 
HOLY ANGEL UNIVERSITY 
Grussenmeyer, W., & Folmer, E. (2016). AudioDraw: user preferences in non-visual 
diagram drawing for touchscreens. Proceedings of the 13th Web for All Conference. 
Hotz, N. (2025, January 2). CRISP-DM for data science teams: 5 actions to consider. 
Data Science PM. 
https://www.datascience-pm.com/crisp-dm-for-data-science-teams-5-actions-to-consider/ 
Lee, J., Peng, Y.-H., Herskovitz, J., & Guo, A. (2024). AltCanvas: A Tile-Based 
Editor for Visual Content Creation with Generative AI for Blind or Visually Impaired People. 
Proceedings of the 26th International ACM SIGACCESS Conference on Computers and 
Accessibility. 
Li, J., Yan, Z., Jarjue, E., Shetty, A., & Peng, H. (2022). TangibleGrid: Tangible Web 
Layout Design for Blind Users. The 35th Annual ACM Symposium on User Interface 
Software and Technology (UIST '22). 
Lim, M., Xu, A., Lin, C., Chen, Z., & Pascual, R. (2022). Developing an automatic 
speech recognizer for Filipino with English code-switching in news broadcast. Proceedings 
of the 2022 14th International Conference on Knowledge and Smart Technology, 13-17. 
https://doi.org/10.1109/KST53302.2022.9727235 
Liu, Y., et al. (2025). LearnAct: Few-Shot Mobile GUI Agent with a Unified 
Demonstration Benchmark. arXiv preprint. 
 
 
 
 
SCHOOL OF COMPUTING

## Page 40

​
              ​
 ​
40   
 
HOLY ANGEL UNIVERSITY 
Mager, M., Gutierrez-Vasques, X., Sierra, G., & Meza-Ruiz, I. (2018). Challenges of 
language technologies for the indigenous languages of the Americas. Proceedings of the 27th 
International Conference on Computational Linguistics, 55–69. 
Malaay, E. M., & Ventura, R. A. (2024). Filipino-English Continuous Speech ASR: 
Towards Application of a Closed Captioning System for Philippine TV News Broadcast. 
J-STAR, 24. 
Mekonnen, F. Y., Al Bataineh, M. F., Abu Abdoun, D., Serag, A., Tamiru, K. T., 
Abula, W., & Darota, S. (2025). Development of a fully autonomous offline assistive system 
for visually impaired individuals: A privacy-first approach. Sensors. 
Mozilla Foundation. (2023). Common Voice: An open source, multi-language dataset 
of voices. Mozilla. https://commonvoice.mozilla.org/ 
Nelson, R. D. O., De Almeida, L. G., & Levenhagen, I. A. (2025). An AI-based 
embedded offline voice assistant for commercial trucks: Enhancing driver safety and HMI 
design. SAE Brasil 2025 Congress. 
Nicolis, M., Siniscalchi, S. M., & Cumani, S. (2023). VOSK: A lightweight, 
cross-platform, offline speech recognition framework. arXiv preprint arXiv:2303.06988. 
Pan, S., Ge, Y., & Sun, X. (2025). A First Look at Privacy Risks of Android 
Task-executable Voice Assistant Applications. arXiv preprint. 
 
 
 
 
SCHOOL OF COMPUTING

## Page 41

​
              ​
 ​
41   
 
HOLY ANGEL UNIVERSITY 
Panayotov, V., Chen, G., Povey, D., & Khudanpur, S. (2015). Librispeech: An ASR 
corpus based on public domain audio books. 2015 IEEE International Conference on 
Acoustics, Speech and Signal Processing (ICASSP), 5206–5210. 
https://doi.org/10.1109/ICASSP.2015.7178964 
 Povey, D., Ghoshal, A., Boulianne, G., Burget, L., Glembek, O., Goel, N., 
Hannemann, M., Motlicek, P., Qian, Y., Schwarz, P., Silovsky, J., Stemmer, G., & Vesely, K. 
(2011). The Kaldi speech recognition toolkit. IEEE 2011 Workshop on Automatic Speech 
Recognition and Understanding. https://doi.org/10.21437/Interspeech.2011-154 
Pradhan, A., Mehta, K., & Findlater, L. (2018, April). " Accessibility Came by 
Accident" Use of Voice-Controlled Intelligent Personal Assistants by People with 
Disabilities. In Proceedings of the 2018 CHI Conference on Human Factors in Computing 
Systems (pp. 1-13). 
Shi, Q., Oztekin, A., Matthew, G., Bortle, J., Jenkins, H., Wong, S., Langlois, P., Zaki, 
A., Coleman, B., Luzuriaga, K., & Zai, A. H. (2025). GrantCheck—an AI Solution for 
Guiding Grant Language to New Policy Requirements: Development Study. JMIR Formative 
Research, 9, e79038. 
Shi, Y., Yu, W., Yao, W., & Liu, N. (2025). Towards Trustworthy GUI Agents: A 
Survey. arXiv preprint. 
 
 
 
 
SCHOOL OF COMPUTING

## Page 42

​
              ​
 ​
42   
 
HOLY ANGEL UNIVERSITY 
Sun, Q., Li, M., Liu, Z., Xie, Z., Xu, F., Yin, Z., ... & Kong, L. (2026). OS-Sentinel: 
Towards Safety-Enhanced Mobile GUI Agents via Hybrid Validation in MobileRisk. ICLR 
2026. 
Torkamani, M. J., & Zarin, I. (2025). Adaptive edge-cloud inference for 
speech-to-action systems using ASR and large language models (ASTA). arXiv preprint. 
Upadhyay, P., Heung, S., Azenkot, S., & Brewer, R. N. (2023, April). Studying 
exploration & long-term use of voice assistants by older adults. In Proceedings of the 2023 
CHI conference on human factors in computing systems (pp. 1-11). 
Vu, M. D., Wang, H., Li, Z., & Chen, C. (2023). Voicify your UI: Towards Android 
app control with voice commands. Proceedings of the ACM on Interactive, Mobile, Wearable 
and Ubiquitous Technologies. 
Wang, F., Zhang, Z., Zhang, X., Wu, Z., Mo, T., Lu, Q., ... & Wang, S. (2024). A 
Comprehensive Survey of Small Language Models in the Era of Large Language Models: 
Techniques, Enhancements, Applications, Collaboration with LLMs, and Trustworthiness. 
arXiv preprint. 
Wang, Y., Zhang, Z., Zhou, W., Zhang, W., Zhang, J., Zhu, Q., ... & He, J. (2026). 
GUIGuard: Toward a General Framework for Privacy-Preserving GUI Agents. arXiv 
preprint. 
 
 
 
 
SCHOOL OF COMPUTING

## Page 43

​
              ​
 ​
43   
 
HOLY ANGEL UNIVERSITY 
Xie, N., Ni, Y., Liu, X., Cao, Y., & Chen, W. (2021). Implementation of a simulation 
control automation tool based on Android accessibility service. Journal of Physics: 
Conference Series. 
Yang, Q., Li, H., Zhao, H., Yan, X., Ding, J., Xu, F., & Li, Y. (2025). FingerTip 20K: 
A Benchmark for Proactive and Personalized Mobile LLM Agents. arXiv preprint. 
Ye, J., Zhang, X., Xu, H., et al. (2025). Mobile-Agent-v3: Fundamental Agents for 
GUI Automation. arXiv preprint. 
Zhang, X., de Greef, L., Swearngin, A., White, S., Murray, K., Yu, L., ... & Bigham, 
J. P. (2021). Screen Recognition: Creating Accessibility Metadata for Mobile Applications 
from Pixels. CHI Conference on Human Factors in Computing Systems. 
Zhu, S., Ma, Y., Feng, J., & Sears, A. (2009). Speech-Based Navigation: Improving 
Grid-Based Solutions. Lect Notes Comput Sci. 
 
 
 
 
 
 
 
 
 
 
 
 
SCHOOL OF COMPUTING

## Page 44

​
              ​
 ​
44   
 
HOLY ANGEL UNIVERSITY 
Appendix A 
RAD Model Lifecycle 
 
Figure 2 illustrates the Rapid Application Development (RAD) lifecycle applied to the 
HandsFree Android overlay construction. The model begins with requirement elicitation and 
modularization, after which parallel development teams independently build modular 
application components—covering the voice command interface, FSM controller, DFS node 
locator, and gesture dispatcher—before integration and final product testing. This iterative 
approach is selected because the dynamic constraints of the Android Accessibility Service 
API require continuous prototyping and testing rather than rigid upfront specification. 
 
 
 
 
 
 
 
 
SCHOOL OF COMPUTING

## Page 45

​
              ​
 ​
45   
 
HOLY ANGEL UNIVERSITY 
Appendix B 
CRISP-DM Lifecycle 
 
Figure 3 presents the CRISP-DM lifecycle governing the HandsFree Speech-to-Intent 
machine learning pipeline. The cyclical process begins with business and data understanding, 
proceeds through data preparation and model training—integrating the lightweight ASR 
model with the Levenshtein Distance algorithm—and culminates in evaluation and 
deployment. The iterative nature of the framework allows the model to be refined based on 
empirical latency and accuracy measurements collected during constrained hardware testing. 
 
 
 
 
 
SCHOOL OF COMPUTING

## Page 46

​
              ​
 ​
46   
 
HOLY ANGEL UNIVERSITY 
Appendix C 
Proposed Architectural Design of HandsFree 
 
Figure 4 presents the proposed architectural design of the HandsFree system, organized into 
four distinct functional layers. The User Input Layer captures spoken commands through the 
device microphone, accepting voice input in Tagalog, English, and Taglish. The HandsFree 
Overlay Layer contains five sequential processing components: the Offline Speech-to-Intent 
(S2I) Engine, which transcribes spoken input on-device; the Translation Layer, which 
normalizes Tagalog and Taglish tokens to English before classification; the Finite State 
Machine (FSM), which governs application state transitions; and the Depth-First Search 
(DFS) Node Locator, which traverses the active accessibility node hierarchy to isolate the 
target UI element. The Operating System Layer interfaces with the Android Accessibility 
Service API to retrieve structural metadata from the active interface and dispatch 
 
 
 
 
SCHOOL OF COMPUTING

## Page 47

​
              ​
 ​
47   
 
HOLY ANGEL UNIVERSITY 
programmatic gesture events. The Target Layer represents the active third-party Android 
application that receives the simulated dispatch gestures as output, requiring no prior 
modification or developer cooperation to function with the system. 
Appendix D 
 
End-User Survey Instrument (Google Forms) 
 
HandsFree Application — End-User Usability Evaluation 
 
Thank you for participating in this evaluation. This survey uses the System Usability Scale 
(SUS) to assess your experience with the HandsFree application. Please answer each item 
based on your experience during the testing session. There are no right or wrong answers. 
 
Section 1: Participant Background 
 
1. Age: ___________  
2. Do you currently use any voice control or hands-free features on your smartphone?  
□ Yes  □ No 
3. How often do you use voice control features on your device? 
□ Never   □ Rarely   □ Sometimes   □ Often   □ Always 
 
Section 2: System Usability Scale (SUS) 
 
For each statement below, indicate your level of agreement on a scale from 1 (Strongly 
Disagree) to 5 (Strongly Agree). 
 
Statement 
1 
2 
3 
4 
5 
1. I think that I would like to use this system 
frequently. 
□ 
□ 
□ 
□ 
□ 
2. I found the system unnecessarily complex. 
□ 
□ 
□ 
□ 
□ 
3. I thought the system was easy to use. 
□ 
□ 
□ 
□ 
□ 
4. I think that I would need the support of a 
technical person to use this system. 
□ 
□ 
□ 
□ 
□ 
5. I found the various functions in this system 
were well integrated. 
□ 
□ 
□ 
□ 
□ 
6. I thought there was too much inconsistency 
in this system. 
□ 
□ 
□ 
□ 
□ 
 
 
 
 
SCHOOL OF COMPUTING

## Page 48

​
              ​
 ​
48   
 
HOLY ANGEL UNIVERSITY 
7. I would imagine that most people would 
learn to use this system very quickly. 
□ 
□ 
□ 
□ 
□ 
8. I found the system very cumbersome to use. 
□ 
□ 
□ 
□ 
□ 
9. I felt very confident using the system. 
□ 
□ 
□ 
□ 
□ 
10. I needed to learn a lot of things before I 
could get going with this system. 
□ 
□ 
□ 
□ 
□ 
 
Section 3: Open-Ended Feedback 
 
1. What did you find most helpful about the HandsFree application? 
 
_______________________________________________________________ 
 
2. What challenges did you encounter while using the application? 
 
_______________________________________________________________ 
 
3. Would you recommend this application to others who could benefit from hands-free 
mobile control?  □ Yes  □ No 
   Why or why not? _______________________________________________ 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
SCHOOL OF COMPUTING

## Page 49

​
              ​
 ​
49   
 
HOLY ANGEL UNIVERSITY 
Appendix E 
 
Expert Evaluator Survey Instrument (Google Forms) 
 
HandsFree Application — Expert Technical Evaluation 
 
This instrument is intended for mobile application developers and Android accessibility 
professionals. Please evaluate the HandsFree application based on your technical expertise. 
Rate each item from 1 (Strongly Disagree) to 5 (Strongly Agree). 
 
Section 1: Expert Background 
 
1. Professional role: ___________ 
2. Primary area of expertise in mobile development: ___________ 
3. Years of experience in mobile application development with Android:  
□ 1-3 years  □ 3-5 years  □ 5 years or more 
 
Section 2: Technical Accuracy (Items 1–5) 
 
Statement 
1 
2 
3 
4 
5 
1. The voice command recognition 
demonstrated acceptable accuracy for its 
intended use. 
□ 
□ 
□ 
□ 
□ 
2. The gesture dispatch (tap/swipe/scroll) 
executed correctly and consistently. 
□ 
□ 
□ 
□ 
□ 
3. The system responded within an acceptable 
time frame for real-time use. 
□ 
□ 
□ 
□ 
□ 
4. The offline processing architecture is 
technically sound and reliable. 
□ 
□ 
□ 
□ 
□ 
5. The Levenshtein-based fuzzy matching 
sufficiently accommodates natural speech 
variation. 
□ 
□ 
□ 
□ 
□ 
 
Section 3: Mobile Usability and Design Standards (Items 6–10) 
 
Statement 
1 
2 
3 
4 
5 
6. The application aligns with established 
general Android mobile usability standards. 
□ 
□ 
□ 
□ 
□ 
7. The system is usable across a range of 
general Android users with varying technical 
familiarity. 
□ 
□ 
□ 
□ 
□ 
 
 
 
 
SCHOOL OF COMPUTING

## Page 50

​
              ​
 ​
50   
 
HOLY ANGEL UNIVERSITY 
8. The system is functional across different 
interaction styles and device types. 
□ 
□ 
□ 
□ 
□ 
9. The privacy-first offline design is appropriate 
for accessibility use cases. 
□ 
□ 
□ 
□ 
□ 
10. The system operates without requiring 
modifications to third-party applications. 
□ 
□ 
□ 
□ 
□ 
 
Section 4: Practical Development Utility (Items 11–15) 
 
Statement 
1 
2 
3 
4 
5 
11. The system is suitable for general daily 
mobile use to a broad user base. 
□ 
□ 
□ 
□ 
□ 
12. The system demonstrates practical value as 
a voice-driven productivity tool. 
□ 
□ 
□ 
□ 
□ 
13. The system reduces reliance on manual 
touch interaction for routine device tasks. 
□ 
□ 
□ 
□ 
□ 
14. The system’s modular design allows for 
practical expansion of supported voice 
commands without significant redevelopment. 
□ 
□ 
□ 
□ 
□ 
15. Overall, the HandsFree system demonstrates 
viability as a practical voice-driven mobile 
application. 
□ 
□ 
□ 
□ 
□ 
 
Section 5: Open-Ended Expert Feedback 
 
1. From a technical perspective, what is the most significant strength of this system? 
 
_______________________________________________________________ 
 
2. What improvements would you recommend before public deployment? 
 
_______________________________________________________________ 
 
 
 
 
SCHOOL OF COMPUTING