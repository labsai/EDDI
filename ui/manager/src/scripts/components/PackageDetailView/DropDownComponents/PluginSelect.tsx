import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import Select from 'react-select';
import * as _ from 'lodash';
import Parser from '../../utils/Parser';
import { CSSProperties } from 'react';
import { IOptions } from '../PackageView';
import {
  IDefaultPluginTypes,
  IPluginExtensions,
} from '../../utils/AxiosFunctions';

const styling: { [key: string]: IExtendedCSSProperties } = {
  dropDown: {
    maxWidth: '40%',
  },
};

interface IOption {
  value: string;
  label: string;
}

interface IState {
  addedExtensions: IOptions[];
  options: IOption[];
  option: IOption;
}
interface IProps {
  packageExtensions: IDefaultPluginTypes[];
  addExtension: (addedExtension: IPluginExtensions) => void;
}

class VersionDropDownComponent extends React.Component<IProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      addedExtensions: [],
      options: [],
      option: null,
    };
  }

  componentDidMount() {
    const options = this.props.packageExtensions.map((option) => ({
      value: option.type,
      label: Parser.getPluginName(option.type, true),
    }));
    this.setState({
      options,
    });
  }

  componentDidUpdate(prevProps) {
    if (prevProps !== this.props) {
      const options = this.props.packageExtensions.map((option) => ({
        value: option.type,
        label: Parser.getPluginName(option.type, true),
      }));
      this.setState({
        options,
      });
    }
  }

  handleSelect = (option) => {
    if (!_.isEmpty(option)) {
      this.setState({ option: this.state.options[option] });
      this.props.addExtension({ type: `eddi://${option.value}` });
    }
  };

  render() {
    return (
      <div style={styling.dropDown}>
        <Select
          placeholder={'Search and add plugins'}
          value={this.state.option}
          options={this.state.options}
          onChange={this.handleSelect}
        />
      </div>
    );
  }
}

const ComposedVersionDropDownComponent: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('VersionDropDownComponent'),
)(VersionDropDownComponent);

export default ComposedVersionDropDownComponent;
