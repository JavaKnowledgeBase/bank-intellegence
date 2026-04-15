from core.database import db
from core.seed_data import seed_database


if __name__ == "__main__":
    db.init_db()
    seed_database()
    print("Seeded CTIP sample data.")
