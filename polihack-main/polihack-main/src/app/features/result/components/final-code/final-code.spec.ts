import { ComponentFixture, TestBed } from '@angular/core/testing';

import { FinalCode } from './final-code';

describe('FinalCode', () => {
  let component: FinalCode;
  let fixture: ComponentFixture<FinalCode>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [FinalCode],
    }).compileComponents();

    fixture = TestBed.createComponent(FinalCode);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
