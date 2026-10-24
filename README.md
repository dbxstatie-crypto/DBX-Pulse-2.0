# DBX Pulse 2.2

Cardul cu câștig NET / km peste Bolt Driver.

## Cum obții APK-ul
**GitHub (fără Android Studio):** creezi un repo, urci tot conținutul acestui folder (inclusiv `.github`),
apoi la *Actions → Build APK → Run workflow*. După ~3 min descarci `DBX-Pulse-apk` → `app-debug.apk`.

**Android Studio:** *File → Open* pe folder, apoi *Build → Build APK(s)*.

## Instalare
1. Prima dată dezinstalează vechiul DriveScore dacă e semnat cu altă cheie. Următoarele versiuni se instalează peste.
2. Deschide aplicația → apasă bannerul de sus → Accesibilitate → activează DBX Pulse.
   (Android 13+: Setări → Aplicații → DBX Pulse → ⋮ → „Permite setări restricționate”, apoi activezi.)
3. Din tabul Setări pui comisionul, consumul, prețul combustibilului etc. Simulatorul arată cardul live.

## Nou în 2.5
- **Cash / card pe card**: chenar verde + iconiță card = card; chenar galben + iconiță monedă + „NUMERAR” = numerar (în Bolt, la card nu apare nimic, la numerar apare „Numerar”).
- **Card = acceptat automat**: cursa se salvează singură după ~2,5 s (dacă nu o respingi) și cardul rămâne ~7 s pe ecran. Se poate opri din Setări.
- **Respingere**: cardul nu mai primește atingeri, deci butoanele Bolt merg normal. Dacă respingi, cursa nu se salvează. Poziția cardului (sus/jos) se schimbă din Setări.
- **Km în gol**: tab Istoric → „Tură”. Cât e pornită tura se numără kilometrii (GPS); km în gol = km în tură − km ai curselor. Tura pornește singură când apare o ofertă și se oprește după 1 h fără activitate în Bolt.
- **Zone**: doar locurile de unde se preiau curse (tu + toți utilizatorii); ofertele văzute nu mai intră în zone și nu se mai trimit în cloud. Locul de preluare = unde ești când apeși „Am ajuns” / „Începe cursa” (dacă pasagerul e la <300 m, locul acceptării). Dacă nu apeși nimic în 45 min, se folosește locul acceptării.

- **Cheltuieli** (Acasă → Cheltuieli): alimentări (lei, litri, km de pe bord) și alte cheltuieli. Calculează consumul real și prețul mediu și le poți aplica în Setări cu un buton. Consumul presupune plin la plin.
- **Când merită** (tab Zone): ore bune (grafic pe 24 de ore) și zile bune pentru cea mai bună zonă sau pentru cercul atins pe hartă. Pentru zilele celorlalți șoferi rulează o dată `supabase/update_zile_saptamana.sql` în Supabase; fără el, zilele vin doar din cursele tale.
- Reparat: funcția `stat()` din ecranul de zone lipsea din sursă.

## Nou în 2.2
- Tab **Zone**: card „Cea mai bună zonă acum” + alte opțiuni, scor normalizat la 100.
- Hartă OpenStreetMap întunecată cu zone **rotunde**; cele fierbinți pulsează (mai repede = mai multă cerere).
- Locația se notează la fiecare ofertă văzută și la fiecare cursă acceptată (permisiune „Permite tot timpul”).
- Build: același workflow GitHub Actions. Cheia debug e aceeași, deci se instalează peste versiunea veche.

## Comunitate (Supabase)
1. Supabase → **Authentication → Sign In / Providers → Email**: activat. Pentru faza cu prieteni, dezactivează **Confirm email** (altfel fiecare trebuie să dea click pe un email; Supabase trimite doar câteva emailuri pe oră pe planul gratuit). Anonymous sign-ins nu mai e necesar.
2. Supabase → **SQL Editor** → lipește `supabase/schema.sql` → Run. La final vezi 2 coduri de invitație (ADM-… pentru tine, PRI-… pentru prieteni).
3. Cheia publică `sb_publishable_...` e deja în `Cloud.kt`. Cheia `secret`/`service_role` NU se pune niciodată în aplicație.
4. Build → instalezi → tab **Zone** → jos, „Cont și comunitate”: „Creez cont” cu email + parolă, apoi nume + cod de invitație. Tu folosești codul ADM-, prietenii codul PRI-.
5. Cont nou pe alt telefon: „Intru în cont” cu același email/parolă — te recunoaște automat ca membru, fără cod nou.
6. Admin: datele complete le vezi în Supabase → Table Editor → `events` (Export → CSV). Prietenii nu pot citi decât zonele agregate.
