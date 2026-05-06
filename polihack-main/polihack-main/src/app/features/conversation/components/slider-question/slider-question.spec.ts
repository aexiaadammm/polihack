import { ComponentFixture, TestBed } from '@angular/core/testing';

import { SliderQuestion } from './slider-question';

describe('SliderQuestion', () => {
  let component: SliderQuestion;
  let fixture: ComponentFixture<SliderQuestion>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SliderQuestion],
    }).compileComponents();

    fixture = TestBed.createComponent(SliderQuestion);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
