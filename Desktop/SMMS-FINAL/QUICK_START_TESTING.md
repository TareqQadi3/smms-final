# SMMS – اختبار النظام بسرعة

## الخطوات السريعة للاختبار

### 1️⃣ تحميل الملفات
```bash
# الملفات المطلوبة موجودة في:
# - /mnt/user-data/outputs/smms-complete/  ← كل ملفات النظام
# - /mnt/user-data/outputs/backend/        ← كود Backend
# - /mnt/user-data/outputs/POSTMAN_COLLECTION.json ← اختبارات API
```

---

## الطريقة السهلة: Docker (موصى به)

### الخطوة 1: جهز البيئة
```bash
# 1. افتح Terminal/CMD
# 2. اذهب لمجلد المشروع
cd smms-complete

# 3. انسخ ملف البيئة
cp .env.example .env

# 4. عدل .env بقيم صحيحة (اختياري - القيم الافتراضية تعمل)
nano .env
```

### الخطوة 2: شغّل النظام
```bash
# build the images
make build

# أو استخدم docker مباشرة
docker compose build

# ابدأ التشغيل
make start

# أو
docker compose up -d

# انتظر 30 ثانية (قاعدة البيانات تبدأ)
sleep 30

# تحقق من الحالة
make status
docker compose ps
```

### الخطوة 3: اختبر الاتصال
```bash
# تحقق من صحة العمل
curl http://localhost:8080/actuator/health

# يجب ترى:
# {"status":"UP"}
```

---

## الطريقة الثانية: بدون Docker (تطوير محلي)

### المتطلبات:
- Java 21+
- Maven 3.9+
- PostgreSQL 16
- Redis 7

### التثبيت:
```bash
# 1. شغّل قاعدة البيانات
docker run --name smms-postgres \
  -e POSTGRES_PASSWORD=changeme \
  -e POSTGRES_DB=smms_db \
  -p 5432:5432 \
  postgres:16-alpine

# 2. شغّل Redis
docker run --name smms-redis \
  -p 6379:6379 \
  redis:7-alpine

# 3. بناء Backend
cd backend
mvn clean package -DskipTests

# 4. تشغيل Backend
java -jar target/smms-backend-1.0.0.jar \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/smms_db \
  --spring.datasource.username=postgres \
  --spring.datasource.password=changeme \
  --app.jwt.secret=$(openssl rand -base64 43)
```

---

## اختبر الـ API

### الطريقة 1: استخدم Postman (الأفضل)

```bash
# 1. افتح Postman
# 2. اذهب إلى: File → Import
# 3. اختر: POSTMAN_COLLECTION.json (من الملفات المرسلة)
# 4. اضغط Import
# 5. في المتغيرات عيّن:
#    - BASE_URL = http://localhost:8080
#    - TOKEN = (سيتم ملؤه تلقائياً)
```

**ثم اتبع هذا الترتيب:**
1. ✅ **Login** (يعطيك TOKEN)
2. ✅ **Create Control Room User**
3. ✅ **Create Technician**
4. ✅ **Create Warehouse**
5. ✅ **Create Approver**
6. ✅ **Submit Ticket** (من قبل Admin)
7. ✅ **Control Room: Review**
8. ✅ **Control Room: Assign**
9. ✅ **Technician: Start Work**
10. ✅ **Technician: Request Materials** (LOOP!)
11. ✅ **Warehouse: Send for Approval**
12. ✅ **Approver: Approve** (يرجع للفني)
13. ✅ **Technician: Complete**
14. ✅ **Control Room: Close**
15. ✅ **Get History** (شوف كل التحويلات)

---

### الطريقة 2: استخدم cURL (سريع)

```bash
# 1. Login وخذ TOKEN
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"sysadmin","password":"Admin@1234"}' \
  | grep -o '"token":"[^"]*' | cut -d'"' -f4)

echo "Token: $TOKEN"

# 2. اختبر الـ API
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/work-orders

# 3. اختبر الـ Health
curl http://localhost:8080/actuator/health
```

---

## ماذا تتوقع أن ترى؟

### عند تشغيل Docker:
```
✓ postgres_1    is up (نقطة سؤال أخضر)
✓ redis_1       is up
✓ backend_1     is up
✓ frontend_1    is up
```

### عند فتح الـ Frontend:
```
http://localhost
↓
صفحة Login
↓
ادخل: username=sysadmin, password=Admin@1234
↓
Dashboard
```

### عند اختبار Workflow:
```
new
  ↓ (control_room يراجع)
under_review
  ↓ (control_room يعين)
assigned
  ↓ (technician يبدأ)
in_progress
  ↓ (technician يطلب مواد)
waiting_materials
  ↓ (warehouse يرسل للموافقة)
waiting_approval
  ↓ (approver يوافق - LOOP!)
in_progress  ← العودة للفني!
  ↓ (technician ينهي)
completed
  ↓ (control_room يغلق)
closed ✓
```

---

## إذا حصلت مشاكل

### Backend لا يشتغل؟
```bash
# شوف الأخطاء
make logs

# أو
docker compose logs backend -f

# تحقق من قاعدة البيانات
docker compose logs postgres -f
```

### Port مستخدم؟
```bash
# غيّر الـ port في .env
SERVER_PORT=8081

# أو أوقف الخدمة القديمة
lsof -i :8080
kill -9 <PID>
```

### قاعدة البيانات لا تشتغل؟
```bash
# أعد البناء بدون cache
docker compose down -v
make build
make start
```

---

## ملخص الملفات المطلوبة

| الملف | الغرض |
|------|-------|
| `docker-compose.yml` | تشغيل كل الخدمات |
| `.env.example` | نسخ لـ .env |
| `backend/pom.xml` | بناء Java |
| `POSTMAN_COLLECTION.json` | اختبار API |
| `Makefile` | أوامر سريعة |
| `health-check.sh` | التحقق من الصحة |

---

## أوامر مفيدة

```bash
# ابدأ
make start

# شوف الحالة
make status

# شوف السجلات
make logs

# أوقف
make stop

# أعد التشغيل
make restart

# نظّف كل شيء
make clean

# احفظ نسخة احتياطية
make backup

# تحقق من الصحة
./health-check.sh
```

---

## الخطوة التالية بعد الاختبار

✅ إذا كل شيء يشتغل:
1. نبني Frontend (React UI)
2. نوصل الـ Frontend بالـ Backend
3. نعمل integration testing
4. نطلق في الإنتاج

---

**أي سؤال؟ اسأل الآن قبل ما تبدأ** 🚀
