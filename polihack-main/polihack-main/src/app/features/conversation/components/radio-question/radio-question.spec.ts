import { ComponentFixture, TestBed } from '@angular/core/testing';

import { RadioQuestion } from './radio-question';

describe('RadioQuestion', () => {
  let component: RadioQuestion;
  let fixture: ComponentFixture<RadioQuestion>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [RadioQuestion],
    }).compileComponents();

    fixture = TestBed.createComponent(RadioQuestion);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
