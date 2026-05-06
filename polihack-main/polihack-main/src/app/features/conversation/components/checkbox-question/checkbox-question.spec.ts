import { ComponentFixture, TestBed } from '@angular/core/testing';

import { CheckboxQuestion } from './checkbox-question';

describe('CheckboxQuestion', () => {
  let component: CheckboxQuestion;
  let fixture: ComponentFixture<CheckboxQuestion>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CheckboxQuestion],
    }).compileComponents();

    fixture = TestBed.createComponent(CheckboxQuestion);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
